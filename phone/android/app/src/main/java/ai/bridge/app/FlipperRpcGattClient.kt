package ai.bridge.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import ai.bridge.BlePacketFragmenter
import ai.bridge.RpcPacketAssembler
import ai.bridge.BridgeFrame
import ai.bridge.BridgeLink
import ai.bridge.BridgeProtocol
import ai.bridge.BridgeType
import ai.bridge.RpcWire
import ai.bridge.ScreenshotReceiver
import ai.bridge.TransferReply
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FlipperRpcGattClient(
    private val context: Context,
    private val listener: Listener,
) : BridgeLink {
    data class TransportStats(val txFrames: Long, val rxFrames: Long, val crcErrors: Long, val retries: Long, val mtu: Int)

    interface Listener {
        fun onStatus(message: String)
        fun onFrame(frame: BridgeFrame)
        fun onRpcState(state: String)
        fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int?, paired: Boolean)
        fun onTransportStats(stats: TransportStats)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("8fe5b3d5-2e7f-4a98-2a48-7acc60fe0000")
        val TX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e61fe0000")
        val RX_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e62fe0000")
        val FLOW_UUID: UUID = UUID.fromString("19ed82ae-ed21-4c9d-4145-228e63fe0000")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_PENDING_BLE_CHUNKS = 64
        private const val MANUAL_LAUNCH_REQUEST = "/ext/apps_data/flipper_ai_bridge/manual_launch"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private var scannerCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var flowCharacteristic: BluetoothGattCharacteristic? = null
    private val pendingWrites = ArrayDeque<ByteArray>()
    private val receivedFrames = ArrayDeque<BridgeFrame>()
    private val completedScreenshots = ArrayDeque<ByteArray>()
    private val frameLock = Object()
    private val streamLock = Object()
    private val rpcAssembler = RpcPacketAssembler()
    private val screenshotReceiver = ScreenshotReceiver()
    @Volatile private var mtuPayload = 20
    @Volatile private var flowCredits = 0
    private var lastProgressBucket = -1
    private val nextRpcCommandId = AtomicInteger(1)
    private val nextBridgeSequence = AtomicLong(1L)
    @Volatile private var ready = false
    @Volatile private var bridgeStarted = false
    private var clearMarkerCommandId = 0
    private var markerStatCommandId = 0
    private var markerStatSentAt = 0L
    private var appStartCommandId = 0
    private var appStartSentAt = 0L
    private val manualLaunchPoll = object : Runnable {
        override fun run() {
            if(!ready || bridgeStarted) return
            val now = SystemClock.elapsedRealtime()
            if(markerStatCommandId != 0 && now - markerStatSentAt > 5_000) markerStatCommandId = 0
            if(appStartCommandId != 0 && now - appStartSentAt > 8_000) appStartCommandId = 0
            if(markerStatCommandId == 0 && appStartCommandId == 0) {
                markerStatCommandId = nextRpcCommandId.getAndIncrement()
                markerStatSentAt = now
                enqueueRpc(RpcWire.encodeStorageStat(markerStatCommandId, MANUAL_LAUNCH_REQUEST))
            }
            mainHandler.postDelayed(this, 1_500)
        }
    }
    private var setupStage = 0
    private var txFrames = 0L
    private var rxFrames = 0L
    private var crcErrors = 0L
    private var retries = 0L

    fun scan() {
        stopScan()
        val bluetooth = adapter
        if(bluetooth == null || !bluetooth.isEnabled) {
            listener.onStatus("Turn on Bluetooth to find Flipper")
            return
        }
        try {
            bluetooth.bondedDevices
                .filter { it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_DUAL }
                .forEach { device ->
                    listener.onDeviceFound(device, device.name?.takeIf(String::isNotBlank) ?: "Paired BLE device", null, true)
                }
        } catch (_: SecurityException) {
            listener.onStatus("Bluetooth connect permission is missing")
            return
        }
        val scanner = bluetooth.bluetoothLeScanner
        if(scanner == null) {
            listener.onStatus("Paired devices shown; Bluetooth LE scan is unavailable")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = try { result.scanRecord?.deviceName ?: device.name ?: "Flipper Zero" } catch (_: SecurityException) { "Flipper Zero" }
                val paired = try { device.bondState == BluetoothDevice.BOND_BONDED } catch (_: SecurityException) { false }
                listener.onDeviceFound(device, name, result.rssi, paired)
            }

            override fun onScanFailed(errorCode: Int) {
                listener.onStatus("BLE scan failed ($errorCode)")
            }
        }
        scannerCallback = callback
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            listener.onStatus("Scanning nearby Flippers… select a device to connect")
        } catch (_: SecurityException) {
            scannerCallback = null
            listener.onStatus("Bluetooth scan permission is missing")
        }
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        closeGattOnly()
        try {
            listener.onStatus("Connecting to selected Flipper…")
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (error: SecurityException) {
            listener.onStatus("Bluetooth connect permission is missing")
        }
    }

    fun isReady(): Boolean = ready

    fun stopScan() {
        val callback = scannerCallback ?: return
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch(_: SecurityException) {
            listener.onStatus("Bluetooth scan permission was revoked")
        }
        scannerCallback = null
    }

    fun close() {
        stopScan()
        closeGattOnly()
        listener.onStatus("Disconnected")
    }

    private fun closeGattOnly() {
        ready = false
        bridgeStarted = false
        mainHandler.removeCallbacks(manualLaunchPoll)
        clearMarkerCommandId = 0
        markerStatCommandId = 0
        appStartCommandId = 0
        synchronized(pendingWrites) { pendingWrites.clear() }
        synchronized(streamLock) { rpcAssembler.reset() }
        screenshotReceiver.resetTransfer()
        synchronized(frameLock) {
            receivedFrames.clear()
            completedScreenshots.clear()
            frameLock.notifyAll()
        }
        try { gatt?.disconnect() } catch(_: SecurityException) { /* Permission was revoked. */ }
        try { gatt?.close() } catch(_: SecurityException) { /* Release local reference below. */ }
        gatt = null
    }

    private fun startBridgeApp() {
        if(!ready || bridgeStarted || appStartCommandId != 0) return
        appStartCommandId = nextRpcCommandId.getAndIncrement()
        appStartSentAt = SystemClock.elapsedRealtime()
        enqueueRpc(RpcWire.encodeStartApp(appStartCommandId, "/ext/apps/Tools/flipper_ai_bridge.fap", "RPC"))
    }

    fun sendBridge(type: BridgeType, payload: ByteArray = byteArrayOf()): Long {
        val sequence = nextBridgeSequence.getAndIncrement() and 0xFFFF_FFFFL
        if(!ready) {
            listener.onStatus("Bridge is not connected")
            return sequence
        }
        send(BridgeFrame(type, sequence, payload))
        return sequence
    }

    override fun send(frame: BridgeFrame) {
        if(!ready) {
            listener.onStatus("Bridge is not connected")
            return
        }
        val rpc = RpcWire.encodeDataExchange(nextRpcCommandId.getAndIncrement(), BridgeProtocol.encode(frame))
        enqueueRpc(rpc, prioritize = frame.type == BridgeType.EMERGENCY_STOP || frame.type == BridgeType.CANCEL)
        txFrames++
        notifyStats()
    }

    override fun await(type: BridgeType, sequenceId: Long, timeoutMillis: Long): BridgeFrame? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        synchronized(frameLock) {
            while(true) {
                if(!ready) return null
                val match = receivedFrames.firstOrNull { it.type == type && it.sequenceId == sequenceId }
                if(match != null) {
                    receivedFrames.remove(match)
                    return match
                }
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if(remaining <= 0) return null
                frameLock.wait(remaining)
            }
        }
    }

    override fun awaitScreenshot(requestSequence: Long, timeoutMillis: Long): ByteArray? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        synchronized(frameLock) {
            while(completedScreenshots.isEmpty()) {
                if(!ready) return null
                val failure = receivedFrames.firstOrNull {
                    it.type == BridgeType.ERROR && it.sequenceId == requestSequence
                }
                if(failure != null) {
                    receivedFrames.remove(failure)
                    val detail = if(failure.payload.size >= 4) {
                        (failure.payload[2].toInt() and 0xFF) or ((failure.payload[3].toInt() and 0xFF) shl 8)
                    } else 0
                    throw IllegalStateException("Flipper screenshot transfer failed ($detail)")
                }
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if(remaining <= 0) return null
                frameLock.wait(remaining)
            }
            return completedScreenshots.removeFirst()
        }
    }

    private fun enqueueRpc(raw: ByteArray, prioritize: Boolean = false) {
        val chunks = BlePacketFragmenter.fragment(raw, mtuPayload)
        synchronized(pendingWrites) {
            if(prioritize) pendingWrites.clear()
            if(pendingWrites.size + chunks.size > MAX_PENDING_BLE_CHUNKS) {
                listener.onStatus("BLE send queue is full; request was not queued")
                return
            }
            chunks.forEach(pendingWrites::addLast)
        }
        pumpWrites()
    }

    private fun pumpWrites() {
        val deviceGatt = gatt ?: return
        val characteristic = rxCharacteristic ?: return
        if(!ready || flowCredits <= 0) return
        val bytes = synchronized(pendingWrites) {
            val next = pendingWrites.peekFirst() ?: return
            if(next.size > flowCredits) return
            pendingWrites.removeFirst()
        }
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = bytes
        val written = try { deviceGatt.writeCharacteristic(characteristic) }
        catch(_: SecurityException) {
            listener.onStatus("Bluetooth permission was revoked; reconnect after granting access")
            return
        }
        if(!written) {
            listener.onStatus("BLE write failed; action was not sent")
            return
        }
        flowCredits -= bytes.size
        mainHandler.postDelayed({ pumpWrites() }, 12)
    }

    private fun onNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if(characteristic.uuid == FLOW_UUID) {
            if(value.size >= 4) {
                flowCredits = ByteBuffer.wrap(value, 0, 4).order(ByteOrder.BIG_ENDIAN).int.coerceAtLeast(0)
                pumpWrites()
            }
            return
        }
        if(characteristic.uuid != TX_UUID) return
        synchronized(streamLock) {
            try {
                rpcAssembler.append(value).forEach(::processRpcMessage)
            } catch(error: Exception) {
                listener.onStatus(error.message ?: "Invalid RPC receive packet")
            }
        }
    }

    private fun processRpcMessage(message: ByteArray) {
        try {
            val fields = RpcWire.parseFields(message)
            val commandId = fields.firstOrNull { it.first == 1 }?.second?.let(RpcWire::decodeVarint)?.toInt()
            val status = fields.firstOrNull { it.first == 2 }?.second?.let(RpcWire::decodeVarint) ?: 0L
            if(commandId == clearMarkerCommandId && clearMarkerCommandId != 0) {
                mainHandler.post {
                    if(commandId == clearMarkerCommandId) {
                        clearMarkerCommandId = 0
                        if(status == 0L || status == 7L) {
                            listener.onStatus("BLE RPC ready; open AI Bridge on Flipper")
                            manualLaunchPoll.run()
                        } else {
                            listener.onStatus("Could not clear old bridge launch request ($status); reconnect")
                        }
                    }
                }
                return
            }
            if(commandId == markerStatCommandId && markerStatCommandId != 0) {
                mainHandler.post {
                    if(commandId == markerStatCommandId) {
                        markerStatCommandId = 0
                        if(status == 0L) startBridgeApp()
                        else if(status != 7L) listener.onStatus("Flipper storage check failed ($status)")
                    }
                }
                return
            }
            if(commandId == appStartCommandId && appStartCommandId != 0) {
                mainHandler.post {
                    if(commandId == appStartCommandId && status != 0L) {
                        appStartCommandId = 0
                        if(status == 17L) listener.onStatus("Waiting for AI Bridge to finish manual launch")
                        else listener.onStatus("Could not open AI Bridge on Flipper ($status)")
                    }
                }
            } else if(status != 0L) {
                listener.onStatus("Flipper RPC reported status $status")
            }
            fields.firstOrNull { it.first == RpcWire.RPC_APP_STATE }?.let { (_, nested) ->
                val state = RpcWire.parseFields(nested).firstOrNull { it.first == 1 }?.second?.let(RpcWire::decodeVarint)
                if(state == 1L) {
                    bridgeStarted = true
                    mainHandler.post {
                        appStartCommandId = 0
                        mainHandler.removeCallbacks(manualLaunchPoll)
                    }
                    listener.onRpcState("Flipper AI Bridge started")
                }
                if(state == 0L) {
                    bridgeStarted = false
                    mainHandler.post {
                        if(ready) {
                            mainHandler.removeCallbacks(manualLaunchPoll)
                            manualLaunchPoll.run()
                        }
                    }
                    listener.onRpcState("Flipper AI Bridge stopped")
                }
            }
            fields.firstOrNull { it.first == RpcWire.RPC_APP_DATA_EXCHANGE }?.let { (_, nested) ->
                val raw = RpcWire.parseFields(nested).firstOrNull { it.first == 1 }?.second
                if(raw != null) {
                    val frame = BridgeProtocol.decode(raw)
                    rxFrames++
                    notifyStats()
                    synchronized(frameLock) {
                        if(frame.type != BridgeType.SCREENSHOT_BEGIN &&
                            frame.type != BridgeType.SCREENSHOT_CHUNK &&
                            frame.type != BridgeType.SCREENSHOT_END) {
                            if(receivedFrames.size >= 64) receivedFrames.removeFirst()
                            receivedFrames.addLast(frame)
                        }
                        frameLock.notifyAll()
                    }
                    if(frame.type == BridgeType.SCREENSHOT_BEGIN ||
                        frame.type == BridgeType.SCREENSHOT_CHUNK ||
                        frame.type == BridgeType.SCREENSHOT_END) {
                        when(val transfer = screenshotReceiver.accept(frame)) {
                            is TransferReply.Ack -> {
                                if(frame.type == BridgeType.SCREENSHOT_BEGIN) lastProgressBucket = 0
                                val bucket = screenshotReceiver.progressPercent() / 10
                                if(frame.type == BridgeType.SCREENSHOT_CHUNK && bucket > lastProgressBucket) {
                                    lastProgressBucket = bucket
                                    listener.onStatus("Screenshot transfer: ${bucket * 10}%")
                                }
                                sendBridge(BridgeType.FILE_ACK, transferReplyPayload(transfer.fileId, transfer.nextOffset))
                            }
                            is TransferReply.Nack -> {
                                retries++
                                notifyStats()
                                sendBridge(BridgeType.FILE_NACK, transferReplyPayload(transfer.fileId, transfer.expectedOffset))
                            }
                            is TransferReply.Complete -> {
                                sendBridge(BridgeType.FILE_ACK, transferReplyPayload(transfer.fileId, transfer.bytes.size.toLong()))
                                synchronized(frameLock) {
                                    if(completedScreenshots.isNotEmpty()) completedScreenshots.removeFirst()
                                    completedScreenshots.addLast(transfer.bytes)
                                    frameLock.notifyAll()
                                }
                                listener.onStatus("Screenshot received (${transfer.bytes.size} bytes; checksum verified)")
                            }
                            is TransferReply.Failed -> listener.onStatus("Screenshot transfer error: ${transfer.reason}")
                        }
                    }
                    listener.onFrame(frame)
                }
            }
        } catch(error: Exception) {
            if(error.message?.contains("CRC", ignoreCase = true) == true) {
                crcErrors++
                notifyStats()
            }
            listener.onStatus("Invalid RPC/bridge packet: ${error.message ?: "decode error"}")
        }
    }

    private fun notifyStats() = listener.onTransportStats(TransportStats(txFrames, rxFrames, crcErrors, retries, mtuPayload + 3))

    private fun transferReplyPayload(fileId: Long, nextOffset: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(fileId.toInt()).putInt(nextOffset.toInt()).array()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if(status != BluetoothGatt.GATT_SUCCESS) {
                ready = false
                try { gatt.close() } catch(_: SecurityException) { /* Permission was revoked. */ }
                if(this@FlipperRpcGattClient.gatt === gatt) this@FlipperRpcGattClient.gatt = null
                listener.onStatus(if(status == 133) "BLE connection failed (133). Check Flipper is awake with Bluetooth on, then retry" else "BLE connection failed ($status)")
                synchronized(frameLock) { frameLock.notifyAll() }
                return
            }
            if(newState == BluetoothGatt.STATE_CONNECTED) {
                listener.onStatus("Connected; discovering authenticated BLE Serial service")
                try {
                    if(!gatt.requestMtu(247)) gatt.discoverServices()
                } catch(_: SecurityException) {
                    listener.onStatus("Bluetooth permission was revoked; grant access and reconnect")
                }
            } else if(newState == BluetoothGatt.STATE_DISCONNECTED) {
                ready = false
                listener.onStatus("BLE disconnected")
                synchronized(frameLock) { frameLock.notifyAll() }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if(status == BluetoothGatt.GATT_SUCCESS) mtuPayload = (mtu - 3).coerceAtMost(243).coerceAtLeast(20)
            notifyStats()
            try { gatt.discoverServices() }
            catch(_: SecurityException) { listener.onStatus("Bluetooth permission was revoked; reconnect") }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if(status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("BLE service discovery failed ($status)")
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if(service == null) {
                listener.onStatus("Flipper BLE Serial service not found")
                return
            }
            rxCharacteristic = service.getCharacteristic(RX_UUID)
            txCharacteristic = service.getCharacteristic(TX_UUID)
            flowCharacteristic = service.getCharacteristic(FLOW_UUID)
            if(rxCharacteristic == null || txCharacteristic == null || flowCharacteristic == null) {
                listener.onStatus("Flipper BLE Serial characteristics are incomplete")
                return
            }
            setupStage = 0
            enableNextNotification(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if(status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("BLE notification setup failed ($status); complete pairing on Flipper")
                return
            }
            setupStage++
            if(setupStage < 2) enableNextNotification(gatt) else {
                val flow = flowCharacteristic
                if(flow != null) {
                    try { gatt.readCharacteristic(flow) }
                    catch(_: SecurityException) { listener.onStatus("Bluetooth permission was revoked; reconnect") }
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if(status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == FLOW_UUID) {
                onNotification(characteristic, characteristic.value ?: byteArrayOf())
                ready = true
                listener.onStatus("BLE RPC ready; preparing manual start")
                mainHandler.post {
                    if(ready) {
                        mainHandler.removeCallbacks(manualLaunchPoll)
                        clearMarkerCommandId = nextRpcCommandId.getAndIncrement()
                        enqueueRpc(RpcWire.encodeStorageDelete(clearMarkerCommandId, MANUAL_LAUNCH_REQUEST))
                    }
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onNotification(characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onNotification(characteristic, value)
        }
    }

    private fun enableNextNotification(gatt: BluetoothGatt) {
        val characteristic = if(setupStage == 0) txCharacteristic else flowCharacteristic
        if(characteristic == null) return
        val enabled = try { gatt.setCharacteristicNotification(characteristic, true) }
        catch(_: SecurityException) {
            listener.onStatus("Bluetooth permission was revoked; reconnect")
            return
        }
        if(!enabled) {
            listener.onStatus("Could not enable BLE notifications")
            return
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if(descriptor == null) {
            listener.onStatus("BLE notification descriptor missing")
            return
        }
        descriptor.value = if(characteristic.uuid == TX_UUID)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val written = try { gatt.writeDescriptor(descriptor) }
        catch(_: SecurityException) {
            listener.onStatus("Bluetooth permission was revoked; reconnect")
            return
        }
        if(!written) listener.onStatus("Could not configure BLE notifications")
    }
}
