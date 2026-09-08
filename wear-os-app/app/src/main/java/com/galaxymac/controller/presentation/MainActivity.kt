package com.galaxymac.controller.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.galaxymac.controller.bluetooth.BleHidManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.galaxymac.controller.haptic.HapticManager
import com.galaxymac.controller.ml.TrainingPhase
import com.galaxymac.controller.model.ActiveGesture
import com.galaxymac.controller.model.ConnectionStatus
import com.galaxymac.controller.model.ControlPacket
import com.galaxymac.controller.network.UdpClient
import com.galaxymac.controller.sensors.GestureEngine
import com.galaxymac.controller.settings.AppSettings

class MainActivity : ComponentActivity() {

    private lateinit var appSettings: AppSettings
    private lateinit var hapticManager: HapticManager
    private lateinit var udpClient: UdpClient
    private lateinit var gestureEngine: GestureEngine
    private lateinit var bleHidManager: BleHidManager

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        bleHidManager.startBleHidServer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        appSettings = AppSettings(this)
        hapticManager = HapticManager(this)
        bleHidManager = BleHidManager(this)
        val coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        udpClient = UdpClient(coroutineScope)
        gestureEngine = GestureEngine(this, udpClient, hapticManager, appSettings, coroutineScope, bleHidManager)

        udpClient.start()
        gestureEngine.start()

        checkAndStartBluetooth()

        setContent {
            MaterialTheme {
                MainAppScreen(
                    udpClient = udpClient,
                    gestureEngine = gestureEngine,
                    appSettings = appSettings,
                    bleHidManager = bleHidManager
                )
            }
        }
    }

    private fun checkAndStartBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissionLauncher.launch(missing.toTypedArray())
            } else {
                bleHidManager.startBleHidServer()
            }
        } else {
            bleHidManager.startBleHidServer()
        }
    }

    override fun onResume() {
        super.onResume()
        gestureEngine.start()
    }

    override fun onPause() {
        super.onPause()
        gestureEngine.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        udpClient.stop()
        gestureEngine.stop()
        bleHidManager.stopBleHidServer()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
            gestureEngine.handleBezelScroll(delta)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    gestureEngine.triggerPinchLeftClick()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                    gestureEngine.triggerDoublePinchRightClick()
                    return true
                }
                KeyEvent.KEYCODE_NAVIGATE_NEXT, KeyEvent.KEYCODE_TAB -> {
                    gestureEngine.triggerPinchLeftClick()
                    return true
                }
                KeyEvent.KEYCODE_NAVIGATE_PREVIOUS -> {
                    gestureEngine.triggerDoublePinchRightClick()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

enum class CurrentScreen {
    MAIN,
    ML_TRAINING,
    SENSITIVITY,
    TOUCHPAD
}

@Composable
fun MainAppScreen(
    udpClient: UdpClient,
    gestureEngine: GestureEngine,
    appSettings: AppSettings,
    bleHidManager: BleHidManager
) {
    var screen by remember { mutableStateOf(CurrentScreen.MAIN) }

    when (screen) {
        CurrentScreen.MAIN -> MainDashboard(
            udpClient = udpClient,
            gestureEngine = gestureEngine,
            appSettings = appSettings,
            bleHidManager = bleHidManager,
            onOpenTraining = { screen = CurrentScreen.ML_TRAINING },
            onOpenSensitivity = { screen = CurrentScreen.SENSITIVITY },
            onOpenTouchpad = { screen = CurrentScreen.TOUCHPAD }
        )
        CurrentScreen.ML_TRAINING -> MLTrainingScreen(
            gestureEngine = gestureEngine,
            appSettings = appSettings,
            onBack = {
                gestureEngine.classifier.cancelTraining()
                screen = CurrentScreen.MAIN
            }
        )
        CurrentScreen.SENSITIVITY -> SensitivitySettingsScreen(
            appSettings = appSettings,
            onBack = { screen = CurrentScreen.MAIN }
        )
        CurrentScreen.TOUCHPAD -> TouchpadScreen(
            onDrag = { dx, dy ->
                udpClient.sendPacket(ControlPacket.Move(dx * 2.5f, dy * 2.5f))
                if (bleHidManager.isConnected.value) {
                    val bDx = (dx * 2.0f).toInt().coerceIn(-127, 127).toByte()
                    val bDy = (dy * 2.0f).toInt().coerceIn(-127, 127).toByte()
                    bleHidManager.sendMouseMove(bDx, bDy)
                }
            },
            onLeftClick = {
                gestureEngine.triggerManualClick("left")
                if (bleHidManager.isConnected.value) bleHidManager.sendLeftClick()
            },
            onRightClick = {
                gestureEngine.triggerManualClick("right")
                if (bleHidManager.isConnected.value) bleHidManager.sendRightClick()
            },
            onExitTouchpad = { screen = CurrentScreen.MAIN }
        )
    }
}

@Composable
fun MainDashboard(
    udpClient: UdpClient,
    gestureEngine: GestureEngine,
    appSettings: AppSettings,
    bleHidManager: BleHidManager,
    onOpenTraining: () -> Unit,
    onOpenSensitivity: () -> Unit,
    onOpenTouchpad: () -> Unit
) {
    val connectionStatus by udpClient.connectionStatus.collectAsState()
    val serverInfo by udpClient.targetServerInfo.collectAsState()
    val activeGesture by gestureEngine.activeGesture.collectAsState()
    val isAirMouseActive by gestureEngine.isAirMouseActive.collectAsState()
    val isAiming by gestureEngine.isAimingMode.collectAsState()

    val isBtConnected by bleHidManager.isConnected.collectAsState()
    val isBtAdv by bleHidManager.isAdvertising.collectAsState()
    val btDevName by bleHidManager.connectedDeviceName.collectAsState()

    val gestureClicksOn by appSettings.gestureClicksEnabled.collectAsState()
    val vibrationOn by appSettings.vibrationEnabled.collectAsState()
    val rollScrollOn by appSettings.wristRollScrollEnabled.collectAsState()
    val useTriplePinch by appSettings.useTriplePinchForSelection.collectAsState()
    val verticalMode by appSettings.verticalAxisMode.collectAsState()

    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scrollState = scrollState) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onRotaryScrollEvent { event ->
                    val delta = event.verticalScrollPixels / 30.0f
                    gestureEngine.handleBezelScroll(delta)
                    true
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 14.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // Native Bluetooth Mouse Status & Pairing Chip
                Chip(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    onClick = {
                        if (!isBtConnected) {
                            bleHidManager.startBleHidServer()
                        }
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = if (isBtConnected) Color(0xFF1B5E20).copy(alpha = 0.5f)
                        else if (isBtAdv) Color(0xFF0D47A1).copy(alpha = 0.5f)
                        else Color(0xFF263238)
                    ),
                    label = {
                        Text(
                            text = if (isBtConnected) "🟢 BT Mouse: Connected"
                            else if (isBtAdv) "🔵 BT: Ready to Pair"
                            else "⚪ BT: Start Pairing",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isBtConnected) Color(0xFF00E676)
                            else if (isBtAdv) Color(0xFF40C4FF)
                            else Color.LightGray
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = if (isBtConnected) (btDevName ?: "Mac")
                            else if (isBtAdv) "Pair in Mac Settings > Bluetooth"
                            else "Tap to pair directly with Mac",
                            fontSize = 8.sp,
                            color = Color.LightGray
                        )
                    }
                )

                ConnectionBadge(status = connectionStatus, info = serverInfo)

                GestureIndicator(gesture = activeGesture, isAiming = isAiming, gestureClicksOn = gestureClicksOn)

                // Air Mouse Toggle Switch
                Chip(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    onClick = {
                        val next = !isAirMouseActive
                        gestureEngine.toggleAirMouse(next)
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = if (isAirMouseActive) Color(0xFF00E5FF).copy(alpha = 0.25f) else Color(0xFF212121)
                    ),
                    label = {
                        Text(
                            text = if (isAirMouseActive) "Air Mouse: ON" else "Air Mouse: OFF",
                            fontWeight = FontWeight.Bold,
                            color = if (isAirMouseActive) Color(0xFF00E5FF) else Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                )

                // Master Toggle: Gesture Clicks ON / OFF (Test Mode)
                Chip(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    onClick = {
                        appSettings.setGestureClicksEnabled(!gestureClicksOn)
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = if (gestureClicksOn) Color(0xFF1B5E20).copy(alpha = 0.4f) else Color(0xFFE65100).copy(alpha = 0.35f)
                    ),
                    label = {
                        Text(
                            text = if (gestureClicksOn) "🖐️ Gesture Clicks: ON" else "🛡️ Gesture Clicks: OFF (Test)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (gestureClicksOn) Color(0xFF00E676) else Color(0xFFFFB74D)
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = if (gestureClicksOn) "Pinches click your Mac" else "Test gestures without clicking!",
                            fontSize = 8.sp,
                            color = Color.LightGray
                        )
                    }
                )

                // 15-Trial ML Pattern Training Screen Button
                Chip(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    onClick = onOpenTraining,
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF311B92)),
                    label = {
                        Text("📉 15-Trial ML Calibration", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB388FF))
                    },
                    secondaryLabel = {
                        Text("Live graph & pattern learning", fontSize = 8.sp, color = Color(0xFFEDE7F6))
                    }
                )

                // Selection Mode Switch (Triple Pinch vs Fist)
                Chip(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    onClick = {
                        appSettings.setUseTriplePinch(!useTriplePinch)
                    },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = if (useTriplePinch) Color(0xFF673AB7).copy(alpha = 0.35f) else Color(0xFF263238)
                    ),
                    label = {
                        Text(
                            text = if (useTriplePinch) "Select: 🤏🤏🤏 Triple Pinch" else "Select: ✊ Fist Clench",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (useTriplePinch) Color(0xFFD1C4E9) else Color(0xFFFFB74D)
                        )
                    }
                )

                // Sensitivity & Dual-Speed Tuning Chip
                Chip(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    onClick = onOpenSensitivity,
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1E2638)),
                    label = {
                        Text("⚙️ Sensitivity & Speeds", fontSize = 10.sp, color = Color(0xFF80D8FF))
                    }
                )

                // Up/Down Axis Mode Selector
                val axisName = when (verticalMode) {
                    0 -> "Flat Wrist (X)"
                    1 -> "Forearm Pitch (Y) ★"
                    2 -> "Inverted X"
                    3 -> "Inverted Y"
                    else -> "Forearm Pitch"
                }
                Chip(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    onClick = { appSettings.cycleVerticalAxis() },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1B242C)),
                    label = {
                        Text("Vertical Axis: $axisName", fontSize = 9.sp, color = Color.White)
                    }
                )

                // Vibration Toggle
                Chip(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    onClick = {
                        val next = !vibrationOn
                        appSettings.setVibrationEnabled(next)
                        gestureEngine.hapticManager.isEnabled = next
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF212121)),
                    label = {
                        Text(
                            text = if (vibrationOn) "📳 Vibration: ON" else "🔕 Vibration: OFF",
                            fontSize = 10.sp,
                            color = if (vibrationOn) Color(0xFF00E676) else Color.LightGray
                        )
                    }
                )

                // 3D Roll Scroll Toggle
                Chip(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    onClick = {
                        appSettings.setWristRollScrollEnabled(!rollScrollOn)
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF263238)),
                    label = {
                        Text(
                            text = if (rollScrollOn) "🔄 Scroll: 3D Roll & Bezel" else "⚙️ Scroll: Bezel ONLY",
                            fontSize = 10.sp,
                            color = if (rollScrollOn) Color(0xFFFF4081) else Color(0xFF64FFDA)
                        )
                    }
                )

                // Quick manual click bar
                Row(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f).height(34.dp),
                        onClick = { gestureEngine.triggerManualClick("left") },
                        colors = ButtonDefaults.primaryButtonColors(backgroundColor = Color(0xFF00B0FF))
                    ) {
                        Text("L-Click", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        modifier = Modifier.weight(1f).height(34.dp),
                        onClick = { gestureEngine.triggerManualClick("right") },
                        colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF7C4DFF))
                    ) {
                        Text("R-Click", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Touchpad Option
                Chip(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    onClick = onOpenTouchpad,
                    colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1E1E24)),
                    label = { Text("Open Touchpad", fontSize = 11.sp) }
                )

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun MLTrainingScreen(
    gestureEngine: GestureEngine,
    appSettings: AppSettings,
    onBack: () -> Unit
) {
    val classifier = gestureEngine.classifier
    val phase by classifier.trainingPhase.collectAsState()
    val sampleCount by classifier.currentSampleCount.collectAsState()
    val liveAcc by gestureEngine.liveAcc.collectAsState()
    val waveformTrigger by classifier.waveformUpdateTrigger.collectAsState()

    val isReviewReady by gestureEngine.isReviewReady.collectAsState()
    val reviewedGesture by gestureEngine.lastReviewedGesture.collectAsState()
    val reviewedCount by gestureEngine.lastReviewedCount.collectAsState()
    val reviewedPeak by gestureEngine.lastReviewedPeak.collectAsState()
    val reviewedInterval by gestureEngine.lastReviewedInterval.collectAsState()

    val scrollState = rememberScrollState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scrollState = scrollState) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0D14))
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            Text("ML Gesture Training & Review", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB388FF))

            // Real-Time Acceleration Waveform Graph (📈📉)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .height(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF141A29))
                    .border(1.dp, Color(0xFF2A3958), RoundedCornerShape(8.dp))
            ) {
                WaveformGraph(
                    buffer = classifier.waveformBuffer,
                    head = classifier.waveformHead,
                    threshold = appSettings.pinchThreshold.collectAsState().value,
                    trigger = waveformTrigger
                )
            }

            // Live Force Readout
            Text(
                text = "Live Force: %.1f m/s²".format(liveAcc),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (liveAcc > 2.5f) Color.Yellow else Color.Gray
            )

            // Step status, Progress bar & instructions
            when (phase) {
                TrainingPhase.IDLE -> {
                    Text(
                        text = "Calibrate 25 natural trials for each gesture to learn your unique biomechanical pattern.",
                        fontSize = 9.sp,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center
                    )

                    Button(
                        modifier = Modifier.fillMaxWidth(0.88f).height(36.dp),
                        onClick = { classifier.startTraining() },
                        colors = ButtonDefaults.primaryButtonColors(backgroundColor = Color(0xFF6200EA))
                    ) {
                        Text("▶ Start 25-Trial Training", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    // Interactive Review only available in idle/testing mode
                    if (isReviewReady) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.95f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E2638))
                                .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(8.dp))
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Detected: $reviewedGesture", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Cyan)
                            Text("Peak: ${"%.1f".format(reviewedPeak)} m/s² | Int: ${reviewedInterval}ms", fontSize = 8.sp, color = Color.LightGray)
                            Text("Was this click correct?", fontSize = 8.sp, color = Color.White)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Button(
                                    modifier = Modifier.weight(1.1f).height(26.dp),
                                    onClick = { gestureEngine.applyReviewFeedback(reviewedCount) },
                                    colors = ButtonDefaults.primaryButtonColors(backgroundColor = Color(0xFF00E676))
                                ) {
                                    Text("✓ Yes", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                                Button(
                                    modifier = Modifier.weight(1f).height(26.dp),
                                    onClick = { gestureEngine.applyReviewFeedback(1) },
                                    colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF00B0FF))
                                ) {
                                    Text("Was 1", fontSize = 8.sp)
                                }
                                Button(
                                    modifier = Modifier.weight(1f).height(26.dp),
                                    onClick = { gestureEngine.applyReviewFeedback(2) },
                                    colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF7C4DFF))
                                ) {
                                    Text("Was 2", fontSize = 8.sp)
                                }
                                Button(
                                    modifier = Modifier.weight(1f).height(26.dp),
                                    onClick = { gestureEngine.applyReviewFeedback(3) },
                                    colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFFAB47BC))
                                ) {
                                    Text("Was 3", fontSize = 8.sp)
                                }
                            }
                        }
                    }
                }
                TrainingPhase.TRAINING_SINGLE_PINCH -> {
                    Text("Phase 1/3: Single Pinch", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                    Text("Tap thumb & index ($sampleCount / 25)", fontSize = 10.sp, color = Color.White)
                    val progress = (sampleCount / 25.0f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF21262D))
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(progress).fillMaxSize().background(Color(0xFF00E5FF)))
                    }
                }
                TrainingPhase.TRAINING_DOUBLE_PINCH -> {
                    Text("Phase 2/3: Double Pinch", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7C4DFF))
                    Text("Double pinch naturally ($sampleCount / 25)", fontSize = 10.sp, color = Color.White)
                    val progress = (sampleCount / 25.0f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF21262D))
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(progress).fillMaxSize().background(Color(0xFF7C4DFF)))
                    }
                }
                TrainingPhase.TRAINING_TRIPLE_PINCH -> {
                    Text("Phase 3/3: Triple Pinch", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFAB47BC))
                    Text("Triple pinch naturally ($sampleCount / 25)", fontSize = 10.sp, color = Color.White)
                    val progress = (sampleCount / 25.0f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF21262D))
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(progress).fillMaxSize().background(Color(0xFFAB47BC)))
                    }
                }
                TrainingPhase.COMPLETED -> {
                    Text("🎉 75-Trial Calibration Complete!", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E676))
                    Text("Learned: Peak=%.1f m/s², Window=%dms".format(classifier.trainedPeakMean, classifier.getRecommendedMultiTapWindow()), fontSize = 8.sp, color = Color.LightGray)
                    Text("High-accuracy model applied & saved.", fontSize = 8.sp, color = Color.LightGray)
                }
            }

            // Cancel / Done Button
            Button(
                modifier = Modifier.fillMaxWidth(0.7f).height(30.dp),
                onClick = onBack,
                colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF37474F))
            ) {
                Text(if (phase == TrainingPhase.COMPLETED) "Done" else "Exit", fontSize = 10.sp)
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
fun WaveformGraph(
    buffer: FloatArray,
    head: Int,
    threshold: Float,
    trigger: Long
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val sizeCount = buffer.size
        val maxVal = 8.0f // max scale 8 m/s²

        // Draw threshold guideline (dashed red line)
        val threshY = height - ((threshold / maxVal).coerceIn(0f, 1f) * height)
        drawLine(
            color = Color(0xFFFF5252).copy(alpha = 0.5f),
            start = Offset(0f, threshY),
            end = Offset(width, threshY),
            strokeWidth = 1.5f
        )

        // Draw waveform path (cyan curve)
        val path = Path()
        val stepX = width / (sizeCount - 1)

        for (i in 0 until sizeCount) {
            val bufferIndex = (head + i) % sizeCount
            val sample = buffer[bufferIndex].coerceIn(0f, maxVal)
            val x = i * stepX
            val y = height - ((sample / maxVal) * height)

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = Color(0xFF00E5FF),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun SensitivitySettingsScreen(
    appSettings: AppSettings,
    onBack: () -> Unit
) {
    val normalSpeed by appSettings.normalSpeed.collectAsState()
    val aimingSpeed by appSettings.aimingSpeed.collectAsState()
    val horizSens by appSettings.horizontalSensitivity.collectAsState()
    val vertSens by appSettings.verticalSensitivity.collectAsState()

    val scrollState = rememberScrollState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scrollState = scrollState) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0C1017))
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            Text("Speed & Sensitivity", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Cyan)

            Chip(
                modifier = Modifier.fillMaxWidth(0.92f),
                onClick = { appSettings.cycleNormalSpeed() },
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1C273A)),
                label = { Text("Normal Speed: ${normalSpeed.toInt()}", fontSize = 10.sp, color = Color.White) },
                secondaryLabel = { Text("Fast screen travel", fontSize = 8.sp, color = Color.Gray) }
            )

            Chip(
                modifier = Modifier.fillMaxWidth(0.92f),
                onClick = { appSettings.cycleAimingSpeed() },
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF2E243A)),
                label = { Text("Aiming Speed: ${aimingSpeed.toInt()} ★", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCE93D8)) },
                secondaryLabel = { Text("Slowdown near 3-dots/buttons", fontSize = 8.sp, color = Color.LightGray) }
            )

            Chip(
                modifier = Modifier.fillMaxWidth(0.92f),
                onClick = { appSettings.cycleHorizontalSensitivity() },
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1F2428)),
                label = { Text("Horizontal: ${"%.2f".format(horizSens)}x", fontSize = 10.sp, color = Color.White) }
            )

            Chip(
                modifier = Modifier.fillMaxWidth(0.92f),
                onClick = { appSettings.cycleVerticalSensitivity() },
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFF1F2428)),
                label = { Text("Vertical: ${"%.2f".format(vertSens)}x", fontSize = 10.sp, color = Color.White) }
            )

            Button(
                modifier = Modifier.fillMaxWidth(0.7f).height(32.dp),
                onClick = onBack,
                colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF424242))
            ) {
                Text("Done", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun ConnectionBadge(status: ConnectionStatus, info: String) {
    val (dotColor, text, subtext) = when (status) {
        ConnectionStatus.CONNECTED -> Triple(Color(0xFF00E676), "Connected", info)
        ConnectionStatus.SEARCHING -> Triple(Color(0xFFFFD600), "Searching Mac...", "Ensure Mac server is running")
        ConnectionStatus.DISCONNECTED -> Triple(Color(0xFFFF5252), "Offline", "Starting...")
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Column {
            Text(text = text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (status == ConnectionStatus.CONNECTED) {
                Text(text = subtext, fontSize = 7.sp, color = Color.LightGray)
            }
        }
    }
}

@Composable
fun GestureIndicator(gesture: ActiveGesture, isAiming: Boolean, gestureClicksOn: Boolean) {
    val (icon, title, color) = when (gesture) {
        ActiveGesture.PINCH_LEFT_CLICK -> Triple("🤏", if (gestureClicksOn) "Pinch: Left Click" else "Pinch (Test Mode)", Color(0xFF00E5FF))
        ActiveGesture.DOUBLE_PINCH_RIGHT_CLICK -> Triple("🤏🤏", if (gestureClicksOn) "Double Pinch: Right Click" else "Double Pinch (Test)", Color(0xFF7C4DFF))
        ActiveGesture.TRIPLE_PINCH_SELECTION -> Triple("🤏🤏🤏", if (gestureClicksOn) "Triple Pinch: Select" else "Triple Pinch (Test)", Color(0xFFAB47BC))
        ActiveGesture.FIST_SELECTION_STARTED, ActiveGesture.FIST_SELECTION_ACTIVE -> Triple("✊", "Selection Active (Drag)", Color(0xFFFF9100))
        ActiveGesture.FIST_SELECTION_ENDED -> Triple("✋", "Selection Ended", Color(0xFF00E676))
        ActiveGesture.WRIST_ROLL_SCROLLING -> Triple("🔄", "Wrist Roll Scroll", Color(0xFFFF4081))
        ActiveGesture.BEZEL_SCROLLING -> Triple("⚙️", "Bezel Scrolling", Color(0xFF64FFDA))
        ActiveGesture.NONE -> if (isAiming) Triple("🎯", "Aiming Mode (Sniper)", Color(0xFF00E5FF)) else Triple("✨", "Ready", Color.Gray)
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(8.dp))
                .background(color.copy(alpha = 0.18f))
                .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "$icon $title", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

@Composable
fun TouchpadScreen(
    onDrag: (Float, Float) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onExitTouchpad: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            Text("Touchpad Mode", fontSize = 11.sp, color = Color.Cyan)

            Text("Swipe finger to move cursor", fontSize = 9.sp, color = Color.Gray)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    modifier = Modifier.size(42.dp),
                    onClick = onLeftClick,
                    colors = ButtonDefaults.primaryButtonColors(backgroundColor = Color(0xFF00B0FF))
                ) {
                    Text("L", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    modifier = Modifier.size(42.dp),
                    onClick = onExitTouchpad,
                    colors = ButtonDefaults.secondaryButtonColors(backgroundColor = Color(0xFF424242))
                ) {
                    Text("✕", fontSize = 12.sp)
                }

                Button(
                    modifier = Modifier.size(42.dp),
                    onClick = onRightClick,
                    colors = ButtonDefaults.primaryButtonColors(backgroundColor = Color(0xFF7C4DFF))
                ) {
                    Text("R", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
