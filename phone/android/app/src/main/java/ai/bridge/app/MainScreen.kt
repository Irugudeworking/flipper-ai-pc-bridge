package ai.bridge.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.bridge.AgentAction
import ai.bridge.ScreenshotReceiver

@Composable
internal fun FlipperBrainApp(vm: MainViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val rootFocusRequester = remember { FocusRequester() }
    LaunchedEffect(rootFocusRequester) { rootFocusRequester.requestFocus() }
    val blePermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result.values.all { it }) vm.scan()
    }
    val cameraPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setCameraEnabled(granted)
        if(!granted) vm.setCameraEnabled(false)
    }
    var fullscreen by remember { mutableStateOf(false) }
    val requestBle = {
        val permissions = if(Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if(permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) vm.scan()
        else blePermissionRequest.launch(permissions)
    }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("FLIPPER AI BRAIN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                val bridgeStatus = when {
                    !state.bridgeRunning -> "Open bridge on Flipper"
                    !state.capabilities.connected -> "Bridge starting"
                    !state.capabilities.keyboard || !state.capabilities.mouse -> "USB HID error"
                    else -> "Bridge ready"
                }
                Text(if(state.connected) "●  ${state.activeDeviceName.ifBlank { "Flipper connected" }} · $bridgeStatus" else "○  ${state.status}", style = MaterialTheme.typography.bodySmall)
            }
        },
        bottomBar = {
            NavigationBar {
                Page.entries.forEach { page ->
                    NavigationBarItem(selected = state.page == page, onClick = { vm.setPage(page) },
                        icon = { Text(pageEmoji(page)) }, label = { Text(page.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }
        }
    ) { insets ->
        Box(Modifier.padding(insets).fillMaxSize().focusRequester(rootFocusRequester).focusable()) {
            when(state.page) {
                Page.BRAIN -> BrainScreen(state, vm, requestBle, cameraPermissionRequest::launch, fullscreen = { fullscreen = true })
                Page.MANUAL -> ManualScreen(state, vm)
                Page.DIAGNOSTICS -> DiagnosticsScreen(state, vm, requestBle)
                Page.SETTINGS -> SettingsScreen(state, vm, cameraPermissionRequest::launch)
                Page.LOGS -> LogsScreen(state)
            }
        }
    }

    if(fullscreen && state.lastScreenshot != null) {
        val bitmap = remember(state.lastScreenshot) { BitmapFactory.decodeByteArray(state.lastScreenshot, 0, state.lastScreenshot!!.size)?.asImageBitmap() }
        AlertDialog(onDismissRequest = { fullscreen = false }, confirmButton = { TextButton(onClick = { fullscreen = false }) { Text("Close") } },
            title = { Text("Latest screen · ${state.screenshotTime}") }, text = {
                bitmap?.let { Image(it, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
            })
    }
}

@Composable
private fun BrainScreen(state: AppUiState, vm: MainViewModel, requestBle: () -> Unit, requestCamera: (String) -> Unit, fullscreen: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 14.dp, bottom = 22.dp)) {
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Flipper Zero", fontWeight = FontWeight.SemiBold)
                            Text(if(state.connected) state.status else "Not connected", style = MaterialTheme.typography.bodySmall)
                        }
                        if(state.connected) OutlinedButton(onClick = vm::disconnect) { Text("DISCONNECT") }
                        else Button(onClick = requestBle) { Text(if(state.scanInProgress) "SCANNING…" else "FIND FLIPPER") }
                    }
                    if(state.connected) Text(when {
                        state.capabilities.keyboard && state.capabilities.mouse -> "Flipper HID available · check USB: HID / CONNECTED on Flipper"
                        state.bridgeRunning && state.capabilities.connected -> "USB HID mode failed on Flipper; see Diagnostics"
                        else -> "Open AI Bridge in Tools on Flipper to start"
                    })
                    if(!state.connected && state.devices.isNotEmpty()) {
                        Divider()
                        Text("Paired and nearby devices", fontWeight = FontWeight.SemiBold)
                        state.devices.forEach { device ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(device.name)
                                    val detail = if(device.paired) "Paired" else "Nearby BLE device"
                                    Text(detail + (device.rssi?.let { " · RSSI $it dBm" } ?: ""), style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = { vm.connect(device) }) { Text("CONNECT") }
                            }
                        }
                    }
                    if(state.scanInProgress) OutlinedButton(onClick = vm::stopScan, modifier = Modifier.fillMaxWidth()) { Text("STOP SCAN") }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LATEST VIEW", fontWeight = FontWeight.SemiBold)
                    val imageBytes = state.lastScreenshot
                    val bitmap = remember(imageBytes) { imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                    if(bitmap != null) Image(bitmap, "Latest captured view", Modifier.fillMaxWidth().height(190.dp).clickable(onClick = fullscreen), contentScale = ContentScale.Fit)
                    else Box(Modifier.fillMaxWidth().height(130.dp).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                        Text("No screenshot yet", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if(state.screenshotTime.isNotBlank()) "Last screenshot: ${state.screenshotTime}" else "Choose a screen source below", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = vm::refreshScreenshot, enabled = state.cameraReady || (state.connected && state.capabilities.screenshotHelper && state.settings.visionMode != VisionMode.CAMERA)) { Text("REFRESH") }
                    }
                    if(state.cameraEnabled) CameraPreview(state, vm)
                    if(state.cameraEnabled) OutlinedButton(onClick = vm::capturePreviewFrame, enabled = state.cameraReady, modifier = Modifier.fillMaxWidth()) { Text("CAPTURE FRAME") }
                    else OutlinedButton(onClick = { requestCamera(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text("USE PHONE CAMERA") }
                }
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Task", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(value = state.task, onValueChange = vm::setTask, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, placeholder = { Text("Describe what you want to do on this PC") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIndicator(state.settings.provider.displayName(), if(state.apiKeyConfigured) "Ready" else "Configure API key", state.apiKeyConfigured)
                        Spacer(Modifier.weight(1f))
                        Text("AI calls ${state.aiCalls}/${state.settings.maxAiCalls}", style = MaterialTheme.typography.bodySmall)
                    }
                    if(state.reason.isNotBlank()) Text("AI: ${state.reason}", style = MaterialTheme.typography.bodySmall)
                    if(state.lastError.isNotBlank()) Text(state.lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    when(state.taskState) {
                        "THINKING", "REQUESTING_SCREENSHOT", "WAITING_SCREENSHOT", "VALIDATING", "SENDING_ACTIONS", "EXECUTING", "WAITING_RESULT" -> {
                            Text("${state.taskState} · Step ${state.step} · ${state.elapsedSeconds}s")
                            if(state.currentAction.isNotBlank()) Text("Current action: ${state.currentAction}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = vm::pauseTask, modifier = Modifier.weight(1f)) { Text("PAUSE") }
                                Button(onClick = vm::stopTask, modifier = Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("STOP", fontWeight = FontWeight.Bold) }
                            }
                        }
                        "PAUSED" -> {
                            Text(if(state.userInteractionRequired) "User interaction required. Complete authentication or permissions manually, then continue." else "Task paused.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = vm::continueTask, modifier = Modifier.weight(1f)) { Text("CONTINUE") }
                                Button(onClick = vm::stopTask, modifier = Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("STOP", fontWeight = FontWeight.Bold) }
                            }
                        }
                        else -> Button(onClick = vm::runTask, enabled = state.connected && state.bridgeRunning && state.apiKeyConfigured, modifier = Modifier.fillMaxWidth()) { Text("RUN TASK") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIndicator(label: String, status: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if(ok) "●" else "○", color = if(ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Text("$label: $status", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CameraPreview(state: AppUiState, vm: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    AndroidView(factory = { ctx -> PreviewView(ctx).also { it.scaleType = PreviewView.ScaleType.FIT_CENTER; preview = it; vm.bindCamera(owner, it) } },
        modifier = Modifier.fillMaxWidth().height(220.dp))
    Text(state.cameraStatus, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ManualScreen(state: AppUiState, vm: MainViewModel) {
    var text by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Manual HID control", style = MaterialTheme.typography.titleMedium)
                Text(if(state.connected && state.capabilities.keyboard) "Connected to ${state.activeDeviceName}. Flipper must show USB: HID / CONNECTED."
                     else "Connect Flipper and confirm keyboard capability", style = MaterialTheme.typography.bodySmall)
                if(state.lastError.isNotBlank()) Text(state.lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { ControlButton("↑") { vm.sendManual(AgentAction.Key(82)) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    ControlButton("←") { vm.sendManual(AgentAction.Key(80)) }
                    ControlButton("↓") { vm.sendManual(AgentAction.Key(81)) }
                    ControlButton("→") { vm.sendManual(AgentAction.Key(79)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.sendManual(AgentAction.MouseClick(1)) }, modifier = Modifier.weight(1f)) { Text("LEFT CLICK") }
                    OutlinedButton(onClick = { vm.sendManual(AgentAction.MouseClick(2)) }, modifier = Modifier.weight(1f)) { Text("RIGHT CLICK") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("WIN" to 0xE3, "ENTER" to 40, "ESC" to 41, "TAB" to 43).forEach { (label, usage) ->
                        FilledTonalButton(
                            onClick = { vm.sendManual(AgentAction.Key(usage)) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
                    }
                }
                OutlinedTextField(text, { text = it.take(96) }, modifier = Modifier.fillMaxWidth(), label = { Text("Text (printable ASCII, max 96)") })
                Button(onClick = { if(text.isNotBlank()) vm.sendManual(AgentAction.Text(text)) }, modifier = Modifier.fillMaxWidth()) { Text("SEND TEXT") }
            } }
        }
        item {
            Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hotkeys", style = MaterialTheme.typography.titleMedium)
                listOf("Win + D" to (8 to 7), "Alt + Tab" to (4 to 43), "Ctrl + A" to (1 to 4), "Ctrl + C" to (1 to 6), "Ctrl + V" to (1 to 25)).forEach { (label, values) ->
                    OutlinedButton(onClick = { vm.sendManual(AgentAction.Hotkey(values.first, values.second)) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
            } }
        }
    }
}

@Composable
private fun ControlButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.padding(3.dp).size(66.dp)) { Text(label, style = MaterialTheme.typography.titleLarge) }
}

@Composable
private fun DiagnosticsScreen(state: AppUiState, vm: MainViewModel, requestBle: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Developer diagnostics", style = MaterialTheme.typography.titleLarge)
        DiagnosticCard("BLE", listOf("State" to state.status, "Selected Flipper" to state.activeDeviceName, "Address" to (state.selectedDevice ?: "Unavailable"), "RSSI" to (state.activeRssi?.let { "$it dBm" } ?: "Unavailable")))
        DiagnosticCard("Protocol", listOf("Status" to if(state.bridgeRunning) "Ready" else "Waiting", "Version" to if(state.capabilities.connected) "${state.capabilities.protocolVersion}" else "Unavailable", "Flipper app version" to "UNSUPPORTED by protocol v1", "Firmware version" to "UNSUPPORTED by protocol v1", "MTU" to "${state.mtu}"))
        DiagnosticCard("Capabilities", listOf("Keyboard" to yesNo(state.capabilities.keyboard), "Relative mouse" to yesNo(state.capabilities.mouse), "Absolute mouse" to "No", "Flipper screenshot helper" to yesNo(state.capabilities.screenshotHelper), "Max packet" to "${state.capabilities.maxPacket}", "Max batch" to "${state.capabilities.maxBatch}", "Max text" to "${state.capabilities.maxText}", "Max file" to "${state.capabilities.maxFileSize / 1024} KB"))
        DiagnosticCard("Transport", listOf("TX frames" to "${state.txPackets}", "RX frames" to "${state.rxPackets}", "CRC errors" to "${state.crcErrors}", "Retries" to "${state.retries}", "Maximum image" to "${ScreenshotReceiver.MAX_FILE_SIZE / 1024} KB", "Last error" to state.lastError.ifBlank { "None" }, "Ping latency" to (state.latencyMs?.let { "$it ms" } ?: "Not measured")))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = requestBle, modifier = Modifier.weight(1f)) { Text("SCAN") }
            Button(onClick = vm::ping, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("PING") }
        }
        if(!state.connected && state.devices.isNotEmpty()) state.devices.forEach { device ->
            OutlinedButton(onClick = { vm.connect(device) }, modifier = Modifier.fillMaxWidth()) {
                Text(device.rssi?.let { "Connect ${device.name} · $it dBm" } ?: "Connect paired ${device.name}")
            }
        }
    }
}

@Composable
private fun DiagnosticCard(title: String, values: List<Pair<String, String>>) {
    Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        values.forEach { (label, value) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    } }
}

@Composable
private fun SettingsScreen(state: AppUiState, vm: MainViewModel, requestCamera: (String) -> Unit) {
    var secret by remember(state.settings.provider) { mutableStateOf("") }
    var openAiModel by remember(state.settings.model) { mutableStateOf(state.settings.model) }
    var geminiModel by remember(state.settings.geminiModel) { mutableStateOf(state.settings.geminiModel) }
    var iterations by remember(state.settings.maxIterations) { mutableStateOf(state.settings.maxIterations.toFloat()) }
    var duration by remember(state.settings.maxDurationMinutes) { mutableStateOf(state.settings.maxDurationMinutes.toFloat()) }
    var actions by remember(state.settings.maxActionsPerStep) { mutableStateOf(state.settings.maxActionsPerStep.toFloat()) }
    var calls by remember(state.settings.maxAiCalls) { mutableStateOf(state.settings.maxAiCalls.toFloat()) }
    var mode by remember(state.settings.visionMode) { mutableStateOf(state.settings.visionMode) }
    var quality by remember(state.settings.screenshotQuality) { mutableStateOf(state.settings.screenshotQuality) }
    var reconnect by remember(state.settings.autoReconnect) { mutableStateOf(state.settings.autoReconnect) }
    var protocolLogging by remember(state.settings.protocolLogging) { mutableStateOf(state.settings.protocolLogging) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)) {
        item { Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI provider", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiBackend.entries.forEach { backend ->
                    Row(Modifier.weight(1f).clickable { vm.selectAiProvider(backend) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.settings.provider == backend, onClick = { vm.selectAiProvider(backend) })
                        Text(backend.displayName())
                    }
                }
            }
            Text("${state.settings.provider.displayName()} multimodal API · HTTPS", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = if(state.settings.provider == AiBackend.OPENAI) openAiModel else geminiModel,
                onValueChange = { if(state.settings.provider == AiBackend.OPENAI) openAiModel = it else geminiModel = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true)
            OutlinedButton(onClick = { vm.saveAiModels(openAiModel, geminiModel) }, modifier = Modifier.fillMaxWidth()) { Text("SAVE MODEL") }
            Text(if(state.apiKeyConfigured) "${state.settings.provider.displayName()} key  ••••••••••••••••  Configured ✓" else "${state.settings.provider.displayName()} key  Not configured", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(secret, { secret = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if(state.apiKeyConfigured) "Enter new key to change" else "${state.settings.provider.displayName()} API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveApiKey(secret); secret = "" }, enabled = secret.isNotBlank(), modifier = Modifier.weight(1f)) { Text(if(state.apiKeyConfigured) "CHANGE KEY" else "SAVE KEY") }
                if(state.apiKeyConfigured) OutlinedButton(onClick = vm::deleteApiKey, modifier = Modifier.weight(1f)) { Text("DELETE KEY") }
            }
            OutlinedButton(onClick = vm::checkApi, enabled = state.apiKeyConfigured && !state.apiCheckBusy, modifier = Modifier.fillMaxWidth()) {
                Text(if(state.apiCheckBusy) "CHECKING API…" else "CHECK API")
            }
            if(state.apiCheckStatus.isNotBlank()) Text(state.apiCheckStatus, style = MaterialTheme.typography.bodySmall)
        } } }
        item { Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Agent limits", style = MaterialTheme.typography.titleMedium)
            LimitSlider("Maximum AI iterations", iterations, 1f..100f) { iterations = it }
            LimitSlider("Maximum task duration · minutes", duration, 1f..60f) { duration = it }
            LimitSlider("Maximum actions per step", actions, 1f..16f) { actions = it }
            LimitSlider("Maximum AI calls per task", calls, 1f..100f) { calls = it }
            Button(onClick = { vm.updateSettings(state.settings.copy(model = openAiModel, geminiModel = geminiModel, maxIterations = iterations.toInt(), maxDurationMinutes = duration.toInt(), maxActionsPerStep = actions.toInt(), maxAiCalls = calls.toInt(), visionMode = mode, screenshotQuality = quality, autoReconnect = reconnect, protocolLogging = protocolLogging)) }, modifier = Modifier.fillMaxWidth()) { Text("SAVE SETTINGS") }
        } } }
        item { Card { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Vision source", style = MaterialTheme.typography.titleMedium)
            Text("Flipper screen capture is designed for Windows 10/11 only. For Linux, use the phone camera.", style = MaterialTheme.typography.bodySmall)
            Text("Screenshot quality sent to the vision model", style = MaterialTheme.typography.bodySmall)
            listOf(ScreenshotQuality.LOW to "Low · fewer image tokens", ScreenshotQuality.HIGH to "High · more visual detail").forEach { (option, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { quality = option }) {
                    RadioButton(selected = quality == option, onClick = { quality = option })
                    Text(label)
                }
            }
            VisionMode.entries.forEach { option -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { mode = option }) {
                RadioButton(selected = mode == option, onClick = { mode = option })
                Text(when(option) { VisionMode.AUTO -> "Auto · Flipper helper, then phone camera"; VisionMode.FLIPPER -> "Flipper screenshot helper"; VisionMode.CAMERA -> "Phone camera" })
            } }
            OutlinedButton(onClick = { requestCamera(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) { Text(if(state.cameraEnabled) "CAMERA ENABLED · ${state.cameraStatus}" else "ENABLE PHONE CAMERA") }
        } } }
        item { Card { Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Bluetooth & developer", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Auto reconnect after app restart", Modifier.weight(1f)); Switch(reconnect, { reconnect = it }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Protocol diagnostics", Modifier.weight(1f)); Switch(protocolLogging, { protocolLogging = it }) }
            Text("Protocol v1 · App 1.0 · Flipper app/firmware version reporting is not present in this FAP protocol.", style = MaterialTheme.typography.bodySmall)
        } } }
        item { Text("About · Flipper AI Brain · protocol v1", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LimitSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${value.toInt()}", style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = (range.endInclusive.toInt() - range.start.toInt() - 1).coerceAtLeast(0))
    }
}

@Composable
private fun LogsScreen(state: AppUiState) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)) {
        item { Text("Activity log", style = MaterialTheme.typography.titleLarge) }
        if(state.logs.isEmpty()) item { Text("No events yet") }
        items(state.logs.reversed()) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun yesNo(value: Boolean) = if(value) "Yes" else "No"
private fun pageEmoji(page: Page) = when(page) { Page.BRAIN -> "◉"; Page.MANUAL -> "⌨"; Page.DIAGNOSTICS -> "⌁"; Page.SETTINGS -> "⚙"; Page.LOGS -> "≡" }
