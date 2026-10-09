package ai.bridge.app

import android.app.Application
import android.bluetooth.BluetoothManager
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Build
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.bridge.AgentAction
import ai.bridge.ActionValidator
import ai.bridge.AgentState
import ai.bridge.AgentStateMachine
import ai.bridge.SafetyPolicy
import ai.bridge.BridgeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream

internal data class NearbyFlipper(val id: String, val name: String, val rssi: Int?, val paired: Boolean, val device: BluetoothDevice)
internal enum class AiBackend { OPENAI, GEMINI }
internal fun AiBackend.displayName(): String = if(this == AiBackend.OPENAI) "OpenAI" else "Gemini"
internal enum class VisionMode { AUTO, FLIPPER, CAMERA }
internal enum class ScreenshotQuality { LOW, HIGH }
internal enum class Page { BRAIN, MANUAL, DIAGNOSTICS, SETTINGS, LOGS }
internal data class AppSettings(
    val provider: AiBackend = AiBackend.OPENAI,
    val model: String = "gpt-4o-mini",
    val geminiModel: String = "gemini-3.8-flash",
    val visionMode: VisionMode = VisionMode.AUTO,
    val screenshotQuality: ScreenshotQuality = ScreenshotQuality.HIGH,
    val maxIterations: Int = 50,
    val maxDurationMinutes: Int = 10,
    val maxActionsPerStep: Int = 10,
    val maxAiCalls: Int = 50,
    val autoReconnect: Boolean = false,
    val protocolLogging: Boolean = false,
)
internal data class AppUiState(
    val page: Page = Page.BRAIN,
    val status: String = "Disconnected",
    val scanInProgress: Boolean = false,
    val devices: List<NearbyFlipper> = emptyList(),
    val selectedDevice: String? = null,
    val activeDeviceName: String = "",
    val activeRssi: Int? = null,
    val connected: Boolean = false,
    val bridgeRunning: Boolean = false,
    val capabilities: DeviceCapabilities = DeviceCapabilities(),
    val lastScreenshot: ByteArray? = null,
    val screenshotTime: String = "",
    val task: String = "",
    val taskState: String = "DISCONNECTED",
    val userInteractionRequired: Boolean = false,
    val step: Int = 0,
    val aiCalls: Int = 0,
    val reason: String = "",
    val currentAction: String = "",
    val elapsedSeconds: Long = 0,
    val openAiKeyConfigured: Boolean = false,
    val geminiKeyConfigured: Boolean = false,
    val apiCheckBusy: Boolean = false,
    val apiCheckStatus: String = "",
    val settings: AppSettings = AppSettings(),
    val cameraEnabled: Boolean = false,
    val cameraReady: Boolean = false,
    val cameraStatus: String = "Camera is off",
    val lastError: String = "",
    val logs: List<String> = emptyList(),
    val txPackets: Long = 0,
    val rxPackets: Long = 0,
    val crcErrors: Long = 0,
    val retries: Long = 0,
    val mtu: Int = 23,
    val latencyMs: Long? = null,
) {
    val apiKeyConfigured: Boolean get() = when(settings.provider) {
        AiBackend.OPENAI -> openAiKeyConfigured
        AiBackend.GEMINI -> geminiKeyConfigured
    }
}

internal class MainViewModel(application: Application) : AndroidViewModel(application), FlipperRpcGattClient.Listener {
    private val prefs = application.getSharedPreferences("flipper_settings", 0)
    private val openAiKeys = SecureKeyStorage(application, AiBackend.OPENAI)
    private val geminiKeys = SecureKeyStorage(application, AiBackend.GEMINI)
    private val cameraSource = CameraVisionSource(application)
    private val client = FlipperRpcGattClient(application, this)
    private val agentStates = AgentStateMachine()
    private val _ui = MutableStateFlow(loadState())
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()
    private var taskJob: Job? = null
    private var apiCheckJob: Job? = null
    private var waitingForUser = false
    private var pausedFrom = AgentState.READY
    private var manualBusy = false
    private var pingStarted: Long? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var manuallyDisconnected = false

    private fun loadState(): AppUiState {
        val mode = runCatching { VisionMode.valueOf(prefs.getString("vision", "AUTO") ?: "AUTO") }.getOrDefault(VisionMode.AUTO)
        val settings = AppSettings(
            provider = runCatching { AiBackend.valueOf(prefs.getString("provider", "OPENAI") ?: "OPENAI") }.getOrDefault(AiBackend.OPENAI),
            model = prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini",
            geminiModel = prefs.getString("gemini_model", "gemini-3.8-flash") ?: "gemini-3.8-flash",
            visionMode = mode,
            screenshotQuality = runCatching { ScreenshotQuality.valueOf(prefs.getString("quality", "HIGH") ?: "HIGH") }.getOrDefault(ScreenshotQuality.HIGH),
            maxIterations = prefs.getInt("iterations", 50).coerceIn(1, 100),
            maxDurationMinutes = prefs.getInt("duration", 10).coerceIn(1, 60),
            maxActionsPerStep = prefs.getInt("actions", 10).coerceIn(1, 16),
            maxAiCalls = prefs.getInt("calls", 50).coerceIn(1, 100),
            autoReconnect = prefs.getBoolean("auto_reconnect", false),
            protocolLogging = prefs.getBoolean("protocol_logging", false),
        )
        return AppUiState(
            openAiKeyConfigured = openAiKeys.isConfigured(), geminiKeyConfigured = geminiKeys.isConfigured(), settings = settings,
            selectedDevice = prefs.getString("selected_address", null),
            activeDeviceName = prefs.getString("selected_name", "") ?: "",
            cameraEnabled = mode == VisionMode.CAMERA &&
                getApplication<Application>().checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }

    init { maybeAutoReconnect() }

    private fun maybeAutoReconnect() {
        val address = prefs.getString("selected_address", null) ?: return
        if(!_ui.value.settings.autoReconnect) return
        val permission = if(Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if(getApplication<Application>().checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return
        try {
            val adapter = (getApplication<Application>().getSystemService(Application.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val device = adapter?.getRemoteDevice(address) ?: return
            changeAgentState(AgentState.CONNECTING)
            _ui.update { it.copy(status = "Reconnecting to selected Flipper…") }
            client.connect(device)
        } catch(_: Exception) { appendLog("Auto reconnect could not start; use Find Flipper") }
    }

    private fun scheduleReconnect() {
        viewModelScope.launch {
            if(manuallyDisconnected || !_ui.value.settings.autoReconnect || _ui.value.connected || reconnectJob?.isActive == true) return@launch
            val delays = longArrayOf(3_000, 6_000, 12_000, 20_000)
            if(reconnectAttempts >= delays.size) return@launch
            val attempt = ++reconnectAttempts
            reconnectJob = launch {
                delay(delays[attempt - 1])
                if(!manuallyDisconnected && _ui.value.settings.autoReconnect && !_ui.value.connected) {
                    appendLog("Retrying Flipper connection ($attempt/${delays.size})")
                    maybeAutoReconnect()
                }
            }
        }
    }

    fun setPage(page: Page) { _ui.update { it.copy(page = page) } }

    fun scan() {
        manuallyDisconnected = true
        reconnectJob?.cancel()
        _ui.update { it.copy(scanInProgress = true, devices = emptyList(), status = "Scanning") }
        client.scan()
    }

    fun stopScan() {
        client.stopScan()
        _ui.update { it.copy(scanInProgress = false, status = if(it.connected) "Connected" else "Disconnected") }
    }

    fun connect(device: NearbyFlipper) {
        manuallyDisconnected = false
        reconnectAttempts = 0
        reconnectJob?.cancel()
        changeAgentState(AgentState.CONNECTING)
        prefs.edit().putString("selected_address", device.id).putString("selected_name", device.name).apply()
        _ui.update { it.copy(selectedDevice = device.id, activeDeviceName = device.name, activeRssi = device.rssi, scanInProgress = false, status = "Connecting") }
        client.connect(device.device)
    }

    fun disconnect() {
        manuallyDisconnected = true
        reconnectJob?.cancel()
        stopTaskAndSendStop("Disconnected by user")
        client.close()
        _ui.update { it.copy(connected = false, bridgeRunning = false, status = "Disconnected", capabilities = DeviceCapabilities()) }
    }

    fun setTask(value: String) { _ui.update { it.copy(task = value.take(1000)) } }

    fun saveApiKey(value: String) {
        val provider = _ui.value.settings.provider
        try {
            keyStore(provider).save(value.trim())
            _ui.update { if(provider == AiBackend.OPENAI) it.copy(openAiKeyConfigured = true, apiCheckStatus = "") else it.copy(geminiKeyConfigured = true, apiCheckStatus = "") }
            appendLog("${provider.displayName()} API key saved securely")
        } catch(error: Exception) { setError(error.message ?: "Could not save key") }
    }

    fun deleteApiKey() {
        val provider = _ui.value.settings.provider
        keyStore(provider).delete()
        _ui.update { if(provider == AiBackend.OPENAI) it.copy(openAiKeyConfigured = false, apiCheckStatus = "") else it.copy(geminiKeyConfigured = false, apiCheckStatus = "") }
        appendLog("${provider.displayName()} API key deleted")
    }

    private fun keyStore(provider: AiBackend): SecureKeyStorage = if(provider == AiBackend.OPENAI) openAiKeys else geminiKeys

    fun selectAiProvider(provider: AiBackend) {
        prefs.edit().putString("provider", provider.name).apply()
        _ui.update { it.copy(settings = it.settings.copy(provider = provider), apiCheckStatus = "") }
    }

    fun checkApi() {
        if(apiCheckJob?.isActive == true) return
        val selected = _ui.value.settings
        val key = keyStore(selected.provider).read()
        if(key.isNullOrBlank()) { setError("Save a ${selected.provider.displayName()} API key first"); return }
        _ui.update { it.copy(apiCheckBusy = true, apiCheckStatus = "Checking ${selected.provider.displayName()}…") }
        apiCheckJob = viewModelScope.launch {
            try {
                val provider: AiProvider = when(selected.provider) {
                    AiBackend.OPENAI -> OpenAiProvider(key, selected.model)
                    AiBackend.GEMINI -> GeminiProvider(key, selected.geminiModel)
                }
                val frame = withContext(Dispatchers.Default) {
                    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        bitmap.recycle()
                        output.toByteArray()
                    }
                }
                provider.getNextAction(AgentRequest(
                    task = "Connection check only. Return FINISHED with no actions.",
                    screenshot = frame,
                    screenshotMimeType = "image/png",
                    screenshotQuality = "LOW",
                    history = emptyList(),
                    capabilities = DeviceCapabilities(),
                    currentState = "Checking API connection; no PC action is allowed",
                ))
                val message = "${selected.provider.displayName()} API works with the selected model"
                _ui.update { it.copy(apiCheckStatus = message) }
                appendLog(message)
            } catch(cancelled: CancellationException) {
                throw cancelled
            } catch(error: Exception) {
                val message = "API check failed: ${error.message ?: "Connection error"}"
                _ui.update { it.copy(apiCheckStatus = message) }
                appendLog(message)
            } finally {
                _ui.update { it.copy(apiCheckBusy = false) }
            }
        }
    }

    fun saveAiModels(openAiModel: String, geminiModel: String) {
        val openAi = openAiModel.trim()
        val gemini = geminiModel.trim()
        if(openAi.isBlank() || gemini.isBlank()) { setError("Model name cannot be empty"); return }
        prefs.edit().putString("model", openAi.take(100)).putString("gemini_model", gemini.take(100)).apply()
        _ui.update { it.copy(settings = it.settings.copy(model = openAi.take(100), geminiModel = gemini.take(100)), apiCheckStatus = "") }
        appendLog("AI model selection saved")
    }

    fun updateSettings(settings: AppSettings) {
        prefs.edit()
            .putString("provider", settings.provider.name)
            .putString("model", settings.model.take(100))
            .putString("gemini_model", settings.geminiModel.take(100))
            .putString("vision", settings.visionMode.name)
            .putString("quality", settings.screenshotQuality.name)
            .putInt("iterations", settings.maxIterations.coerceIn(1, 100))
            .putInt("duration", settings.maxDurationMinutes.coerceIn(1, 60))
            .putInt("actions", settings.maxActionsPerStep.coerceIn(1, 16))
            .putInt("calls", settings.maxAiCalls.coerceIn(1, 100))
            .putBoolean("auto_reconnect", settings.autoReconnect)
            .putBoolean("protocol_logging", settings.protocolLogging)
            .apply()
        _ui.update { it.copy(settings = settings.copy(
            maxIterations = settings.maxIterations.coerceIn(1, 100),
            maxDurationMinutes = settings.maxDurationMinutes.coerceIn(1, 60),
            maxActionsPerStep = settings.maxActionsPerStep.coerceIn(1, 16),
            maxAiCalls = settings.maxAiCalls.coerceIn(1, 100))) }
        if(!settings.autoReconnect) reconnectJob?.cancel()
    }

    fun setCameraEnabled(enabled: Boolean) {
        _ui.update { it.copy(cameraEnabled = enabled, cameraReady = false, cameraStatus = if(enabled) "Opening camera…" else "Camera is off") }
        if(!enabled) cameraSource.unbind()
    }

    fun bindCamera(owner: androidx.lifecycle.LifecycleOwner, view: androidx.camera.view.PreviewView) {
        cameraSource.bind(owner, view) { ready ->
            _ui.update { it.copy(cameraReady = ready, cameraStatus = if(ready) "Camera ready · captures only when requested" else "Could not open camera") }
        }
    }

    fun capturePreviewFrame() {
        if(taskJob?.isActive == true) { setError("Stop or pause the task before taking a manual frame"); return }
        viewModelScope.launch {
            try { acceptScreenshot(cameraSource.capture()) }
            catch(error: Exception) { setError(error.message ?: "Camera capture failed") }
        }
    }

    fun refreshScreenshot() {
        if(taskJob?.isActive == true) { setError("The agent is already requesting screenshots"); return }
        val source = selectVisionSource(_ui.value)
        if(source == null) { setError("No screenshot source is available; enable the camera or Flipper helper"); return }
        viewModelScope.launch {
            try { acceptScreenshot(source.capture()) }
            catch(error: Exception) {
                if(_ui.value.settings.visionMode == VisionMode.AUTO && source is FlipperScreenshotVisionSource && _ui.value.cameraReady) {
                    appendLog("Flipper screen capture failed: ${error.message ?: "unknown error"}; using phone camera")
                    try { acceptScreenshot(cameraSource.capture()) }
                    catch(cameraError: Exception) { setError(cameraError.message ?: "Camera capture failed") }
                } else setError(error.message ?: "Screenshot request failed")
            }
        }
    }

    fun runTask() {
        if(taskJob?.isActive == true) return
        val state = _ui.value
        if(!state.connected || !state.bridgeRunning || !state.capabilities.connected) { setError("Connect a Flipper and wait for protocol capabilities first"); return }
        if(state.task.isBlank()) { setError("Enter a task first"); return }
        val backend = state.settings.provider
        val key = keyStore(backend).read()
        if(key.isNullOrBlank()) { setError("Save a ${backend.displayName()} API key in Settings first"); return }
        var vision: VisionSource = selectVisionSource(state) ?: run {
            setError("Screen capture unavailable. Enable the Flipper screenshot helper or select Phone Camera and grant camera access.")
            return
        }
        val provider: AiProvider = when(backend) {
            AiBackend.OPENAI -> OpenAiProvider(key, state.settings.model)
            AiBackend.GEMINI -> GeminiProvider(key, state.settings.geminiModel)
        }
        taskJob = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            var calls = 0
            var step = 0
            val history = ArrayDeque<String>()
            changeAgentState(AgentState.REQUESTING_SCREENSHOT)
            _ui.update { it.copy(step = 0, aiCalls = 0, reason = "", lastError = "", elapsedSeconds = 0, userInteractionRequired = false) }
            appendLog("Task started")
            try {
                while(step < _ui.value.settings.maxIterations) {
                    checkActiveAndWait()
                    val live = _ui.value
                    if(SystemClock.elapsedRealtime() - started > live.settings.maxDurationMinutes * 60_000L) {
                        finishTask(AgentState.FINISHED, "Task duration limit reached"); return@launch
                    }
                    if(calls >= live.settings.maxAiCalls) { finishTask(AgentState.FINISHED, "AI call limit reached"); return@launch }
                    step++
                    changeAgentState(AgentState.REQUESTING_SCREENSHOT)
                    _ui.update { it.copy(step = step, elapsedSeconds = (SystemClock.elapsedRealtime() - started) / 1000) }
                    appendLog("Step $step · requesting screenshot")
                    changeAgentState(AgentState.WAITING_SCREENSHOT)
                    val frame = try {
                        vision.capture()
                    } catch(cancelled: CancellationException) {
                        throw cancelled
                    } catch(error: Exception) {
                        if(_ui.value.settings.visionMode == VisionMode.AUTO && vision is FlipperScreenshotVisionSource && _ui.value.cameraReady) {
                            appendLog("Flipper screenshot failed; falling back to the phone camera")
                            vision = cameraSource
                            vision.capture()
                        } else throw error
                    }
                    acceptScreenshot(frame)
                    checkActiveAndWait()
                    changeAgentState(AgentState.THINKING)
                    _ui.update { it.copy(aiCalls = calls + 1) }
                    calls++
                    val decision = provider.getNextAction(AgentRequest(
                        task = _ui.value.task,
                        screenshot = frame.bytes,
                        screenshotMimeType = frame.mimeType,
                        screenshotQuality = _ui.value.settings.screenshotQuality.name,
                        history = history.toList(),
                        capabilities = _ui.value.capabilities,
                        currentState = _ui.value.reason.ifBlank { "Connected and ready" },
                    ))
                    checkActiveAndWait()
                    changeAgentState(AgentState.VALIDATING)
                    _ui.update { it.copy(reason = decision.reason) }
                    appendLog("AI decision: ${decision.status} · ${decision.reason.take(140)}")
                    if(SafetyPolicy.requiresHumanInteraction(decision.reason)) {
                        waitingForUser = true
                        pausedFrom = AgentState.VALIDATING
                        changeAgentState(AgentState.PAUSED)
                        _ui.update { it.copy(userInteractionRequired = true, reason = "User interaction required: ${decision.reason}") }
                        appendLog("Authentication or permission interaction detected; waiting for user")
                        while(waitingForUser) { checkActiveOnly(); delay(150) }
                        continue
                    }
                    when(decision.status) {
                        DecisionStatus.FINISHED -> { finishTask(AgentState.FINISHED, decision.reason.ifBlank { "Task complete" }); return@launch }
                        DecisionStatus.FAILED -> { finishTask(AgentState.ERROR, decision.reason.ifBlank { "AI marked task as failed" }); return@launch }
                        DecisionStatus.NEED_USER -> {
                            waitingForUser = true
                            pausedFrom = AgentState.VALIDATING
                            changeAgentState(AgentState.PAUSED)
                            _ui.update { it.copy(userInteractionRequired = true, reason = decision.reason.ifBlank { "User interaction required" }) }
                            appendLog("Waiting for manual user interaction")
                            while(waitingForUser) { checkActiveOnly(); delay(150) }
                            continue
                        }
                        DecisionStatus.NEED_SCREENSHOT -> { changeAgentState(AgentState.REQUESTING_SCREENSHOT); continue }
                        DecisionStatus.CONTINUE -> Unit
                    }
                    val actions = decision.actions.takeWhile { it != AgentAction.Finish }
                    if(actions.isNotEmpty()) {
                        ActionValidator.validate(actions, _ui.value.settings.maxActionsPerStep)
                        SafetyPolicy.validate(actions, _ui.value.settings.maxActionsPerStep)
                        validateCapabilities(actions, _ui.value.capabilities)
                    }
                    if(actions.isNotEmpty()) {
                        changeAgentState(AgentState.SENDING_ACTIONS)
                        _ui.update { it.copy(currentAction = actionLabel(actions.first())) }
                        val result = executeActions(actions)
                        history.addLast("${actions.size} action(s): ${actions.joinToString { actionLabel(it) }} → $result")
                        while(history.size > 12) history.removeFirst()
                        if(result != "SUCCESS") throw IllegalStateException("Flipper action result: $result")
                    }
                    if(decision.actions.any { it == AgentAction.Finish }) {
                        finishTask(AgentState.FINISHED, decision.reason.ifBlank { "Task complete" }); return@launch
                    }
                    if(!decision.requestScreenshotAfter && actions.isEmpty()) {
                        finishTask(AgentState.ERROR, "AI returned no action and did not request a new screenshot"); return@launch
                    }
                }
                finishTask(AgentState.FINISHED, "Iteration limit reached")
            } catch(cancelled: CancellationException) {
                // STOP/disconnect sets its own final state synchronously.
                throw cancelled
            } catch(error: Exception) {
                val reason = error.message ?: "Task failed"
                if(client.isReady()) client.sendBridge(BridgeType.EMERGENCY_STOP)
                changeAgentState(AgentState.ERROR)
                _ui.update { it.copy(lastError = reason, currentAction = "", elapsedSeconds = (SystemClock.elapsedRealtime() - started) / 1000) }
                appendLog("Task stopped: $reason")
            }
        }
    }

    private fun selectVisionSource(state: AppUiState): VisionSource? = when(state.settings.visionMode) {
        VisionMode.FLIPPER -> if(state.capabilities.screenshotHelper) FlipperScreenshotVisionSource(client) { _ui.value.capabilities } else null
        VisionMode.CAMERA -> if(state.cameraReady) cameraSource else null
        VisionMode.AUTO -> when {
            state.capabilities.screenshotHelper -> FlipperScreenshotVisionSource(client) { _ui.value.capabilities }
            state.cameraReady -> cameraSource
            else -> null
        }
    }

    fun pauseTask() {
        if(taskJob?.isActive == true && _ui.value.taskState !in listOf("PAUSED", "FINISHED", "STOPPED", "ERROR")) {
            waitingForUser = true
            pausedFrom = agentStates.current()
            changeAgentState(AgentState.PAUSED)
            appendLog("Task paused")
        }
    }

    fun continueTask() {
        waitingForUser = false
        _ui.update { it.copy(userInteractionRequired = false) }
        if(agentStates.current() == AgentState.PAUSED) changeAgentState(pausedFrom)
        appendLog("Task continued")
    }

    fun stopTask() = stopTaskAndSendStop("Stopped by user")

    private fun stopTaskAndSendStop(reason: String) {
        waitingForUser = false
        taskJob?.cancel()
        taskJob = null
        val stopSent = client.isReady() && _ui.value.bridgeRunning
        if(stopSent) client.sendBridge(BridgeType.EMERGENCY_STOP)
        if(agentStates.current() in setOf(AgentState.DISCONNECTED, AgentState.CONNECTING)) {
            changeAgentState(AgentState.DISCONNECTED)
        } else {
            changeAgentState(AgentState.STOPPED)
        }
        _ui.update { it.copy(currentAction = "", lastError = "", reason = reason, userInteractionRequired = false) }
        appendLog(if(stopSent) "Emergency stop sent · $reason" else reason)
    }

    fun sendManual(action: AgentAction) {
        if(taskJob?.isActive == true) { setError("Stop or pause the AI task before sending a manual action"); return }
        if(!_ui.value.connected || !client.isReady()) { setError("Connect Flipper before using Manual controls"); return }
        if(manualBusy) return
        manualBusy = true
        viewModelScope.launch {
            try {
                ActionValidator.validate(listOf(action), 1)
                validateCapabilities(listOf(action), _ui.value.capabilities)
                val result = executeActions(listOf(action))
                appendLog("Manual action ${actionLabel(action)} · $result")
                if(result != "SUCCESS") setError("Flipper returned $result")
            } catch(error: Exception) { setError(error.message ?: "Manual action failed") }
            finally { manualBusy = false }
        }
    }

    private suspend fun checkActiveAndWait() {
        while(waitingForUser) { checkActiveOnly(); delay(150) }
        checkActiveOnly()
    }
    private fun checkActiveOnly() {
        if(!client.isReady() || !_ui.value.connected) throw IllegalStateException("Connection lost; task stopped for safety")
    }

    private suspend fun executeActions(actions: List<AgentAction>): String = withContext(Dispatchers.IO) {
        if(taskJob?.isActive == true) changeAgentState(AgentState.EXECUTING)
        for(action in actions) {
            if(action == AgentAction.RequestScreenshot || action == AgentAction.Finish) continue
            val (type, payload) = encodeDirect(action)
            val id = client.sendBridge(type, payload)
            if(taskJob?.isActive == true) changeAgentState(AgentState.WAITING_RESULT)
            _ui.update { it.copy(currentAction = actionLabel(action)) }
            val result = client.await(BridgeType.ACTION_RESULT, id, 35_000)
                ?: throw IllegalStateException("Flipper action timed out")
            val code = result.payload.getOrNull(0)?.toInt()?.and(0xFF) ?: -1
            val completed = result.payload.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
            appendLog("${actionLabel(action)} · ${resultName(code)} ($completed)")
            if(code != 0) return@withContext resultName(code)
        }
        "SUCCESS"
    }

    private fun encodeDirect(action: AgentAction): Pair<BridgeType, ByteArray> = when(action) {
        is AgentAction.Key -> BridgeType.KEY_TAP to u16(action.usage)
        is AgentAction.KeyDown -> BridgeType.KEY_DOWN to u16(action.usage)
        is AgentAction.KeyUp -> BridgeType.KEY_UP to u16(action.usage)
        is AgentAction.Hotkey -> BridgeType.HOTKEY to byteArrayOf(action.modifiers.toByte(), action.usage.toByte(), (action.usage ushr 8).toByte())
        is AgentAction.Text -> BridgeType.TEXT_INPUT to action.value.toByteArray(Charsets.US_ASCII)
        is AgentAction.MouseMove -> BridgeType.MOUSE_MOVE to ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(action.dx.toShort()).putShort(action.dy.toShort()).array()
        is AgentAction.MouseClick -> BridgeType.MOUSE_CLICK to byteArrayOf(action.button.toByte())
        is AgentAction.MouseScroll -> BridgeType.MOUSE_SCROLL to u16(action.delta)
        is AgentAction.Wait -> BridgeType.WAIT to ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(action.millis.toInt()).array()
        AgentAction.RequestScreenshot -> BridgeType.REQUEST_SCREENSHOT to byteArrayOf()
        AgentAction.Finish -> throw IllegalArgumentException("FINISH is not a transport action")
    }

    private fun validateCapabilities(actions: List<AgentAction>, caps: DeviceCapabilities) {
        if(actions.any { it is AgentAction.Key || it is AgentAction.KeyDown || it is AgentAction.KeyUp || it is AgentAction.Hotkey || it is AgentAction.Text } && !caps.keyboard) throw IllegalArgumentException("UNSUPPORTED: Flipper has no keyboard capability")
        if(actions.any { it is AgentAction.MouseMove || it is AgentAction.MouseClick || it is AgentAction.MouseScroll } && !caps.mouse) throw IllegalArgumentException("UNSUPPORTED: Flipper has no mouse capability")
    }

    fun ping() {
        if(!client.isReady()) { setError("Connect first"); return }
        pingStarted = SystemClock.elapsedRealtime()
        val id = client.sendBridge(BridgeType.PING, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(pingStarted!!).array())
        viewModelScope.launch(Dispatchers.IO) {
            val frame = client.await(BridgeType.PONG, id, 5_000)
            val latency = if(frame == null) null else SystemClock.elapsedRealtime() - (pingStarted ?: SystemClock.elapsedRealtime())
            _ui.update { it.copy(latencyMs = latency) }
            appendLog(if(latency == null) "PING timed out" else "PING latency ${latency}ms")
        }
    }

    private fun acceptScreenshot(frame: VisionFrame) {
        _ui.update { it.copy(lastScreenshot = frame.bytes, screenshotTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())) }
        appendLog("Screenshot received and validated (${frame.bytes.size / 1024} KB · ${frame.mimeType})")
    }

    private fun actionLabel(action: AgentAction): String = when(action) {
        is AgentAction.Key -> "Key tap"
        is AgentAction.KeyDown -> "Key down"
        is AgentAction.KeyUp -> "Key up"
        is AgentAction.Hotkey -> "Hotkey"
        is AgentAction.Text -> "TEXT_INPUT [redacted]"
        is AgentAction.MouseMove -> "Mouse move"
        is AgentAction.MouseClick -> "Mouse click"
        is AgentAction.MouseScroll -> "Mouse scroll"
        is AgentAction.Wait -> "Wait ${action.millis}ms"
        AgentAction.RequestScreenshot -> "Request screenshot"
        AgentAction.Finish -> "Finish"
    }

    private fun resultName(code: Int): String = when(code) {
        0 -> "SUCCESS"; 1 -> "FAILED"; 2 -> "TIMEOUT"; 3 -> "CANCELLED"; 4 -> "UNSUPPORTED"; 5 -> "INVALID"; 6 -> "BUSY"; else -> "UNKNOWN"
    }
    private fun u16(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun finishTask(state: AgentState, reason: String) {
        changeAgentState(state)
        _ui.update { it.copy(reason = reason, lastError = if(state == AgentState.ERROR) reason else "", currentAction = "") }
        appendLog("Task ${state.name} · ${reason.take(160)}")
    }

    private fun changeAgentState(next: AgentState) {
        if(agentStates.current() == next) return
        try { agentStates.transition(next) }
        catch (_: IllegalArgumentException) {
            // A newly started task may replace a terminal state after the previous loop ended.
            if(next == AgentState.REQUESTING_SCREENSHOT && agentStates.current() in setOf(AgentState.FINISHED, AgentState.ERROR, AgentState.STOPPED)) {
                agentStates.transition(next)
            } else if(next == AgentState.DISCONNECTED) {
                agentStates.transition(next)
            } else throw IllegalStateException("Unsafe agent transition ${agentStates.current()} → $next")
        }
        _ui.update { it.copy(taskState = next.name) }
    }

    private fun setError(message: String) {
        _ui.update { it.copy(lastError = message) }
        appendLog("Error · $message")
    }

    private fun appendLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _ui.update { it.copy(logs = (it.logs + "$time  $message").takeLast(400)) }
    }

    override fun onStatus(message: String) {
        _ui.update { current ->
            val connected = when {
                message.contains("BLE RPC ready", true) || message.contains("Connected;", true) -> true
                message.contains("disconnected", true) || message == "Disconnected" || message.contains("connection failed", true) -> false
                else -> current.connected
            }
            val scanning = when {
                message.startsWith("Scanning nearby", true) -> true
                message.contains("scan failed", true) || message.contains("scan is unavailable", true) || message.contains("Turn on Bluetooth", true) -> false
                else -> current.scanInProgress
            }
            current.copy(status = message, connected = connected, scanInProgress = scanning)
        }
        appendLog(message)
        if(message.contains("BLE RPC ready", true) || message.contains("Connected;", true)) {
            reconnectJob?.cancel()
            reconnectAttempts = 0
        }
        if(message.contains("BLE disconnected", true) || message.contains("connection failed", true)) {
            val interruptedTask = taskJob?.isActive == true
            taskJob?.cancel()
            changeAgentState(if(interruptedTask) AgentState.ERROR else AgentState.DISCONNECTED)
            _ui.update { it.copy(lastError = if(interruptedTask) "Connection lost. The previous task has been stopped for safety." else it.lastError, currentAction = "", bridgeRunning = false) }
            scheduleReconnect()
        }
    }

    override fun onFrame(frame: ai.bridge.BridgeFrame) {
        when(frame.type) {
            BridgeType.HELLO_ACK -> {
                appendLog("Flipper protocol v1 acknowledged")
                client.sendBridge(BridgeType.GET_CAPABILITIES)
            }
            BridgeType.CAPABILITIES -> {
                if(frame.payload.size == 14) {
                    val input = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                    val caps = DeviceCapabilities(input.short.toInt() and 0xFFFF, input.int, input.short.toInt() and 0xFFFF,
                        input.get().toInt() and 0xFF, input.get().toInt() and 0xFF, input.int.toLong() and 0xFFFF_FFFFL)
                    changeAgentState(AgentState.READY)
                    _ui.update { it.copy(capabilities = caps, bridgeRunning = true, status = "Connected · protocol v${caps.protocolVersion}") }
                    appendLog(caps.describe())
                } else appendLog("Rejected capabilities payload with invalid length")
            }
            BridgeType.PONG -> {
                val started = pingStarted
                _ui.update { it.copy(latencyMs = if(started == null) null else SystemClock.elapsedRealtime() - started) }
            }
            BridgeType.ERROR -> appendLog("Flipper reported protocol error")
            else -> Unit
        }
    }

    override fun onRpcState(state: String) {
        appendLog(state)
        if(state == "Flipper AI Bridge started") {
            _ui.update { it.copy(bridgeRunning = true) }
            client.sendBridge(BridgeType.HELLO)
        } else if(state == "Flipper AI Bridge stopped") {
            _ui.update { it.copy(bridgeRunning = false, capabilities = DeviceCapabilities()) }
        }
    }

    override fun onDeviceFound(device: BluetoothDevice, name: String, rssi: Int?, paired: Boolean) {
        val id = try { device.address } catch (_: SecurityException) { device.hashCode().toString() }
        _ui.update { state ->
            val previous = state.devices.firstOrNull { it.id == id }
            val item = NearbyFlipper(id, name.ifBlank { previous?.name ?: "Bluetooth device" }, rssi ?: previous?.rssi, paired || previous?.paired == true, device)
            state.copy(devices = (state.devices.filterNot { it.id == id } + item)
                .sortedWith(compareByDescending<NearbyFlipper> { it.paired }.thenByDescending { it.rssi ?: Int.MIN_VALUE }))
        }
    }

    override fun onTransportStats(stats: FlipperRpcGattClient.TransportStats) {
        _ui.update { it.copy(txPackets = stats.txFrames, rxPackets = stats.rxFrames, crcErrors = stats.crcErrors, retries = stats.retries, mtu = stats.mtu) }
    }

    override fun onCleared() {
        reconnectJob?.cancel()
        taskJob?.cancel()
        cameraSource.close()
        client.close()
        super.onCleared()
    }
}
