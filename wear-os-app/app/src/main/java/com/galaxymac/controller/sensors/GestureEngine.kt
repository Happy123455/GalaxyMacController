package com.galaxymac.controller.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.galaxymac.controller.bluetooth.BleHidManager
import com.galaxymac.controller.haptic.HapticManager
import com.galaxymac.controller.ml.PinchPatternClassifier
import com.galaxymac.controller.ml.TrainingPhase
import com.galaxymac.controller.model.ActiveGesture
import com.galaxymac.controller.model.ControlPacket
import com.galaxymac.controller.network.UdpClient
import com.galaxymac.controller.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

class GestureEngine(
    private val context: Context,
    private val udpClient: UdpClient,
    val hapticManager: HapticManager,
    val appSettings: AppSettings,
    private val scope: CoroutineScope,
    val bleHidManager: BleHidManager
) : SensorEventListener {

    private val TAG = "GestureEngine"

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _activeGesture = MutableStateFlow(ActiveGesture.NONE)
    val activeGesture: StateFlow<ActiveGesture> = _activeGesture

    private val _isAirMouseActive = MutableStateFlow(true)
    val isAirMouseActive: StateFlow<Boolean> = _isAirMouseActive

    val isAimingMode = MutableStateFlow(false)

    // ML Pattern Classifier instance
    val classifier = PinchPatternClassifier()

    // Live sensor values for telemetry
    val liveGx = MutableStateFlow(0.0f)
    val liveGy = MutableStateFlow(0.0f)
    val liveGz = MutableStateFlow(0.0f)
    val liveAcc = MutableStateFlow(0.0f)

    // Calibration states
    var isCalibratingPinch: Boolean = false
    var isCalibratingFist: Boolean = false
    val calibrationStatus = MutableStateFlow("Ready")

    // Anti-Shake Motion Freeze timestamp (locks cursor during bezel scroll, pinch, double pinch, triple pinch)
    private var motionFreezeUntil: Long = 0L
    private var smoothX = 0.0f
    private var smoothY = 0.0f

    // Multi-Pinch counter state machine & physical debounce lockout
    private var pinchCounter: Int = 0
    private var pendingPinchJob: Job? = null
    private val MULTI_PINCH_WINDOW_MS = 380L
    private var lastSinglePinchTime: Long = 0L
    private var lastPinchAcceptedTime: Long = 0L
    private val MIN_INTER_PINCH_INTERVAL_MS = 160L // Physical debounce: eliminates flesh/strap recoil ringing!

    // Real-Time Gesture Review & Inspection State
    val lastReviewedGesture = MutableStateFlow("None")
    val lastReviewedCount = MutableStateFlow(0)
    val lastReviewedPeak = MutableStateFlow(0.0f)
    val lastReviewedInterval = MutableStateFlow(0L)
    val isReviewReady = MutableStateFlow(false)

    // Pulse tracking
    private var pulseStartTime: Long = 0L
    private var isPulseActive: Boolean = false
    private var maxPulseMagnitude: Float = 0.0f
    private var lastGestureTime: Long = 0L
    private var lastShakeTime: Long = 0L

    var isSelecting: Boolean = false
        private set

    // Roll calibration
    private var calibratedRollAngle: Float = 0.0f
    private var isRollCalibrated: Boolean = false
    private var currentRollAngle: Float = 0.0f
    private var lastScrollSentTime: Long = 0

    // Rate limiting (60Hz max)
    private var lastMovePacketTime: Long = 0
    private val MOVE_PACKET_INTERVAL_NS = 16_000_000L // ~60fps
    private val DEADZONE_THRESHOLD = 0.012f
    private val AIMING_SPEED_THRESHOLD = 0.15f // rad/s
    private val WRIST_ROLL_DEADZONE_DEG = 14.0f

    init {
        hapticManager.isEnabled = appSettings.vibrationEnabled.value
    }

    fun freezeCursorMotion(durationMs: Long) {
        val target = SystemClock.uptimeMillis() + durationMs
        if (target > motionFreezeUntil) {
            motionFreezeUntil = target
        }
    }

    fun start() {
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        linearAccSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        Log.i(TAG, "Gesture Engine started.")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        pendingPinchJob?.cancel()
        if (isSelecting) {
            endSelection()
        }
        Log.i(TAG, "Gesture Engine stopped.")
    }

    fun toggleAirMouse(active: Boolean) {
        _isAirMouseActive.value = active
        if (!active && isSelecting) {
            endSelection()
        }
    }

    fun calibrateNeutralPosition() {
        calibratedRollAngle = currentRollAngle
        isRollCalibrated = true
        hapticManager.vibrateClick()
        Log.i(TAG, "Calibrated neutral wrist roll: $calibratedRollAngle deg")
    }

    fun startPinchTraining() {
        isCalibratingPinch = true
        isCalibratingFist = false
        calibrationStatus.value = "Pinch fingers NOW!"
    }

    fun startFistTraining() {
        isCalibratingFist = true
        isCalibratingPinch = false
        calibrationStatus.value = "Clench fist NOW!"
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> handleGyroscope(event)
            Sensor.TYPE_LINEAR_ACCELERATION -> handleLinearAcceleration(event)
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Gyroscope handler with intelligent "About to Click" (Aiming Mode) slowdown
     * and anti-jitter pre-click freezing.
     */
    private fun handleGyroscope(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]

        liveGx.value = gx
        liveGy.value = gy
        liveGz.value = gz

        if (!_isAirMouseActive.value) return

        val nowMs = SystemClock.uptimeMillis()
        if (nowMs < motionFreezeUntil) {
            smoothX = 0.0f
            smoothY = 0.0f
            return
        }

        val nowNs = event.timestamp
        if (nowNs - lastMovePacketTime < MOVE_PACKET_INTERVAL_NS) return
        lastMovePacketTime = nowNs

        val rawX = -gz
        val rawY = when (appSettings.verticalAxisMode.value) {
            0 -> -gx
            1 -> -gy
            2 -> gx
            3 -> gy
            else -> -gy
        }

        val rawSpeed = sqrt(rawX * rawX + rawY * rawY)
        val aiming = rawSpeed < AIMING_SPEED_THRESHOLD
        isAimingMode.value = aiming

        val targetX = if (abs(rawX) > DEADZONE_THRESHOLD) rawX else 0.0f
        val targetY = if (abs(rawY) > DEADZONE_THRESHOLD) rawY else 0.0f

        val alpha = if (aiming) 0.85f else 0.65f
        smoothX = smoothX * (1f - alpha) + targetX * alpha
        smoothY = smoothY * (1f - alpha) + targetY * alpha

        if (abs(smoothX) < 0.003f && abs(smoothY) < 0.003f) return

        val baseSpeed = if (aiming) appSettings.aimingSpeed.value else appSettings.normalSpeed.value
        val horizSens = appSettings.horizontalSensitivity.value
        val vertSens = appSettings.verticalSensitivity.value

        val dx = smoothX * baseSpeed * horizSens
        val dy = smoothY * baseSpeed * vertSens

        udpClient.sendPacket(ControlPacket.Move(dx, dy))

        if (bleHidManager.isConnected.value) {
            val bDx = dx.toInt().coerceIn(-127, 127).toByte()
            val bDy = dy.toInt().coerceIn(-127, 127).toByte()
            if (bDx != 0.toByte() || bDy != 0.toByte()) {
                bleHidManager.sendMouseMove(bDx, bDy)
            }
        }
    }

    /**
     * Linear acceleration handler with physical recoil debounce
     */
    private fun handleLinearAcceleration(event: SensorEvent) {
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        liveAcc.value = magnitude
        classifier.pushWaveformSample(magnitude)

        val now = SystemClock.uptimeMillis()

        // 1. Quick Calibration Recording
        if (isCalibratingPinch) {
            if (magnitude > 1.8f) {
                isCalibratingPinch = false
                val newThresh = magnitude * 0.80f
                appSettings.setPinchThreshold(newThresh)
                calibrationStatus.value = "Pinch calibrated: %.1f m/s²".format(newThresh)
                hapticManager.vibrateClick()
            }
            return
        }

        if (isCalibratingFist) {
            if (magnitude > 2.0f) {
                isCalibratingFist = false
                val newThresh = magnitude * 0.80f
                appSettings.setFistThreshold(newThresh)
                calibrationStatus.value = "Fist calibrated: %.1f m/s²".format(newThresh)
                hapticManager.vibrateHoldStart()
            }
            return
        }

        // 0. Violent Shake Detection for Grab / Hold / Drag Toggle
        if (magnitude >= 17.5f) {
            if (now - lastShakeTime > 800L) {
                lastShakeTime = now
                freezeCursorMotion(400L)
                toggleSelection(isTriplePinch = false)
                Log.i(TAG, "✊ Violent Shake detected -> Toggle Selection (isSelecting=$isSelecting)")
                return
            }
        }

        val pinchThresh = appSettings.pinchThreshold.value
        val fistThresh = appSettings.fistThreshold.value

        // 2. Pulse Start & Pre-Click Motion Lock
        if (magnitude > pinchThresh) {
            if (!isPulseActive) {
                isPulseActive = true
                pulseStartTime = now
                maxPulseMagnitude = magnitude
                freezeCursorMotion(240L)
            } else {
                if (magnitude > maxPulseMagnitude) {
                    maxPulseMagnitude = magnitude
                }
            }
        }

        // 3. Pulse Finish & Duration Separation with Physical Debounce
        if (isPulseActive && (magnitude <= pinchThresh * 0.70f || (now - pulseStartTime) > 220L)) {
            isPulseActive = false
            val pulseDuration = now - pulseStartTime

            if (pulseDuration < 105L) {
                // Check physical debounce lockout:
                val timeSinceLastPinch = now - lastPinchAcceptedTime
                if (timeSinceLastPinch < MIN_INTER_PINCH_INTERVAL_MS) {
                    // Suppress flesh/strap recoil ringing oscillation!
                    Log.d(TAG, "Suppressed recoil bounce: $timeSinceLastPinch ms < $MIN_INTER_PINCH_INTERVAL_MS ms")
                    return
                }
                lastPinchAcceptedTime = now
                onPinchDetected(now, maxPulseMagnitude, pulseDuration)
            } else if (!appSettings.useTriplePinchForSelection.value && (pulseDuration >= 130L || maxPulseMagnitude >= fistThresh)) {
                if (now - lastGestureTime > 750L) {
                    lastGestureTime = now
                    toggleSelection()
                }
            }
        }
    }

    /**
     * Multi-Pinch Dispatcher with Ground-Truth Inspection
     */
    private fun onPinchDetected(now: Long, peak: Float, duration: Long) {
        val phase = classifier.trainingPhase.value

        // Guided 25-Trial Training: Phase 1 (Single Pinch)
        if (phase == TrainingPhase.TRAINING_SINGLE_PINCH) {
            hapticManager.vibrateClick()
            val snapshot = classifier.getRecentWaveformSnapshot()
            udpClient.sendPacket(ControlPacket.WaveformEvent("Single Pinch", 1, peak, 0L, snapshot))
            val done = classifier.recordSinglePinchTrial(peak, duration)
            if (done) hapticManager.vibrateDoubleFistRightClick()
            return
        }

        pinchCounter++
        pendingPinchJob?.cancel()
        hapticManager.vibrateClick()

        val interval = if (lastSinglePinchTime > 0) now - lastSinglePinchTime else 220L
        lastSinglePinchTime = now

        // Guided 25-Trial Training: Phase 2 (Double Pinch)
        if (phase == TrainingPhase.TRAINING_DOUBLE_PINCH) {
            if (pinchCounter == 2) {
                pinchCounter = 0
                pendingPinchJob?.cancel()
                val snapshot = classifier.getRecentWaveformSnapshot()
                udpClient.sendPacket(ControlPacket.WaveformEvent("Double Pinch", 2, peak, interval, snapshot))
                val done = classifier.recordDoublePinchTrial(interval)
                if (done) hapticManager.vibrateDoubleFistRightClick()
            } else {
                pendingPinchJob = scope.launch(Dispatchers.Main) {
                    delay(500)
                    pinchCounter = 0
                }
            }
            return
        }

        // Guided 25-Trial Training: Phase 3 (Triple Pinch)
        if (phase == TrainingPhase.TRAINING_TRIPLE_PINCH) {
            if (pinchCounter == 3) {
                pinchCounter = 0
                pendingPinchJob?.cancel()
                val snapshot = classifier.getRecentWaveformSnapshot()
                udpClient.sendPacket(ControlPacket.WaveformEvent("Triple Pinch", 3, peak, interval, snapshot))
                val done = classifier.recordTriplePinchTrial(interval)
                if (done) {
                    val recThresh = classifier.getRecommendedThreshold()
                    appSettings.setPinchThreshold(recThresh)
                    hapticManager.vibrateHoldEnd()
                }
            } else {
                pendingPinchJob = scope.launch(Dispatchers.Main) {
                    delay(500)
                    pinchCounter = 0
                }
            }
            return
        }

        // Operational Mode: Immediate Triple Pinch -> RIGHT CLICK
        if (pinchCounter >= 3) {
            pinchCounter = 0
            pendingPinchJob?.cancel()
            pendingPinchJob = null

            lastReviewedGesture.value = "Triple Pinch -> Right Click"
            lastReviewedCount.value = 3
            lastReviewedPeak.value = peak
            lastReviewedInterval.value = interval
            isReviewReady.value = true

            val snapshot = classifier.getRecentWaveformSnapshot()
            udpClient.sendPacket(ControlPacket.GestureReview("Triple Pinch (Right Click)", 3, peak, interval))
            udpClient.sendPacket(ControlPacket.WaveformEvent("Triple Pinch", 3, peak, interval, snapshot))
            triggerDoublePinchRightClick()
            return
        }

        // Multi-Pinch Window Timer
        pendingPinchJob = scope.launch(Dispatchers.Main) {
            delay(MULTI_PINCH_WINDOW_MS)
            val count = pinchCounter
            pinchCounter = 0
            pendingPinchJob = null

            val gName = when (count) {
                1 -> "Single Pinch (Ignored)"
                2 -> "Double Pinch -> Left Click"
                else -> "Triple Pinch -> Right Click"
            }

            lastReviewedGesture.value = gName
            lastReviewedCount.value = count
            lastReviewedPeak.value = peak
            lastReviewedInterval.value = interval
            isReviewReady.value = true

            val snapshot = classifier.getRecentWaveformSnapshot()
            udpClient.sendPacket(ControlPacket.GestureReview(gName, count, peak, interval))
            udpClient.sendPacket(ControlPacket.WaveformEvent(gName, count, peak, interval, snapshot))

            when (count) {
                1 -> {
                    // User requested: "forget about single click it is messing us some times"
                    Log.i(TAG, "Single pinch ignored (no click dispatched).")
                }
                2 -> {
                    // User requested: "double click means left click"
                    triggerPinchLeftClick()
                }
                else -> {
                    // User requested: "tripal click means right click"
                    triggerDoublePinchRightClick()
                }
            }
        }
    }

    /**
     * Active human-in-the-loop review feedback
     */
    fun applyReviewFeedback(actualCount: Int) {
        val detected = lastReviewedCount.value
        Log.i(TAG, "User Review Feedback: actual=$actualCount, detected=$detected")

        if (actualCount == 1 && detected >= 2) {
            // User did 1 pinch, but watch saw 2 -> recoil issue, slightly raise threshold
            val cur = appSettings.pinchThreshold.value
            appSettings.setPinchThreshold((cur + 0.3f).coerceAtMost(6.0f))
        } else if (actualCount >= 2 && detected == 1) {
            // User did double pinch, but watch only saw 1 -> slightly lower threshold
            val cur = appSettings.pinchThreshold.value
            appSettings.setPinchThreshold((cur - 0.25f).coerceAtLeast(2.0f))
        }

        hapticManager.vibrateClick()
        isReviewReady.value = false
    }

    fun dismissReview() {
        isReviewReady.value = false
    }

    /**
     * Rotation vector handler (3D Roll Scroll)
     */
    private fun handleRotationVector(event: SensorEvent) {
        if (!appSettings.wristRollScrollEnabled.value) {
            if (_activeGesture.value == ActiveGesture.WRIST_ROLL_SCROLLING) {
                _activeGesture.value = ActiveGesture.NONE
            }
            return
        }

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        val rollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
        currentRollAngle = rollDeg

        if (!isRollCalibrated) {
            calibratedRollAngle = rollDeg
            isRollCalibrated = true
        }

        val deltaRoll = currentRollAngle - calibratedRollAngle
        val now = SystemClock.uptimeMillis()

        // Re-center neutral baseline when arm is resting near center
        if (abs(deltaRoll) <= 8.0f) {
            calibratedRollAngle = calibratedRollAngle * 0.96f + currentRollAngle * 0.04f
            if (_activeGesture.value == ActiveGesture.WRIST_ROLL_SCROLLING) {
                _activeGesture.value = ActiveGesture.NONE
            }
            return
        }

        if (abs(deltaRoll) > WRIST_ROLL_DEADZONE_DEG) {
            if (now - lastScrollSentTime >= 40) {
                lastScrollSentTime = now

                // User: "clockwise arme twist down and anticloke side anr twist up"
                // deltaRoll > 0 (clockwise): scroll DOWN (negative scrollY)
                // deltaRoll < 0 (anti-clockwise): scroll UP (positive scrollY)
                val scrollY = if (deltaRoll > 0) {
                    val excess = deltaRoll - WRIST_ROLL_DEADZONE_DEG
                    -(excess * 3.8f).coerceAtLeast(-90.0f)
                } else {
                    val excess = -deltaRoll - WRIST_ROLL_DEADZONE_DEG
                    (excess * 3.8f).coerceAtMost(90.0f)
                }

                _activeGesture.value = ActiveGesture.WRIST_ROLL_SCROLLING
                udpClient.sendPacket(ControlPacket.Scroll(scrollX = 0.0f, scrollY = scrollY))
                if (bleHidManager.isConnected.value) {
                    val wheel = if (scrollY > 0) 1.toByte() else (-1).toByte()
                    bleHidManager.sendScroll(wheel)
                }
            }
        } else {
            if (_activeGesture.value == ActiveGesture.WRIST_ROLL_SCROLLING) {
                _activeGesture.value = ActiveGesture.NONE
            }
        }
    }

    /**
     * Hardware Bezel rotation handler
     */
    fun handleBezelScroll(delta: Float) {
        freezeCursorMotion(450L)

        _activeGesture.value = ActiveGesture.BEZEL_SCROLLING
        hapticManager.vibrateScrollTick()

        val scrollAmount = -delta * 40.0f
        udpClient.sendPacket(ControlPacket.Scroll(scrollX = 0.0f, scrollY = scrollAmount))

        if (bleHidManager.isConnected.value) {
            val wheel = (-delta * 2).toInt().coerceIn(-127, 127).toByte()
            bleHidManager.sendScroll(wheel)
        }

        scope.launch(Dispatchers.Main) {
            delay(120)
            if (_activeGesture.value == ActiveGesture.BEZEL_SCROLLING) {
                _activeGesture.value = ActiveGesture.NONE
            }
        }
    }

    fun triggerPinchLeftClick() {
        freezeCursorMotion(280L)
        _activeGesture.value = ActiveGesture.PINCH_LEFT_CLICK

        if (appSettings.gestureClicksEnabled.value) {
            udpClient.sendPacket(ControlPacket.Click(button = "left", action = "click"))
            if (bleHidManager.isConnected.value) {
                bleHidManager.sendLeftClick()
            }
            Log.i(TAG, "Gesture: PINCH -> Left Click")
        } else {
            Log.i(TAG, "Gesture Test: PINCH -> Left Click (Clicks disabled)")
        }

        scope.launch(Dispatchers.Main) {
            delay(200)
            if (_activeGesture.value == ActiveGesture.PINCH_LEFT_CLICK) {
                _activeGesture.value = ActiveGesture.NONE
            }
        }
    }

    fun triggerDoublePinchRightClick() {
        freezeCursorMotion(320L)
        _activeGesture.value = ActiveGesture.DOUBLE_PINCH_RIGHT_CLICK
        hapticManager.vibrateDoubleFistRightClick()

        if (appSettings.gestureClicksEnabled.value) {
            udpClient.sendPacket(ControlPacket.Click(button = "right", action = "click"))
            if (bleHidManager.isConnected.value) {
                bleHidManager.sendRightClick()
            }
            Log.i(TAG, "Gesture: DOUBLE PINCH -> Right Click")
        } else {
            Log.i(TAG, "Gesture Test: DOUBLE PINCH -> Right Click (Clicks disabled)")
        }

        scope.launch(Dispatchers.Main) {
            delay(250)
            if (_activeGesture.value == ActiveGesture.DOUBLE_PINCH_RIGHT_CLICK) {
                _activeGesture.value = ActiveGesture.NONE
            }
        }
    }

    fun triggerTriplePinchSelection() {
        freezeCursorMotion(380L)
        toggleSelection(isTriplePinch = true)
    }

    fun toggleSelection(isTriplePinch: Boolean = false) {
        if (isSelecting) {
            endSelection(isTriplePinch)
        } else {
            startSelection(isTriplePinch)
        }
    }

    private fun startSelection(isTriplePinch: Boolean = false) {
        freezeCursorMotion(350L)
        isSelecting = true
        _activeGesture.value = if (isTriplePinch) ActiveGesture.TRIPLE_PINCH_SELECTION else ActiveGesture.FIST_SELECTION_STARTED
        hapticManager.vibrateHoldStart()

        if (appSettings.gestureClicksEnabled.value) {
            udpClient.sendPacket(ControlPacket.Click(button = "left", action = "down"))
            if (bleHidManager.isConnected.value) {
                bleHidManager.startDrag()
            }
            Log.i(TAG, "Gesture: Selection Started (Mouse Down)")
        }

        scope.launch(Dispatchers.Main) {
            delay(350)
            if (isSelecting) {
                _activeGesture.value = ActiveGesture.FIST_SELECTION_ACTIVE
            }
        }
    }

    fun endSelection(isTriplePinch: Boolean = false) {
        freezeCursorMotion(350L)
        isSelecting = false
        _activeGesture.value = if (isTriplePinch) ActiveGesture.TRIPLE_PINCH_SELECTION else ActiveGesture.FIST_SELECTION_ENDED
        hapticManager.vibrateHoldEnd()

        if (appSettings.gestureClicksEnabled.value) {
            udpClient.sendPacket(ControlPacket.Click(button = "left", action = "up"))
            if (bleHidManager.isConnected.value) {
                bleHidManager.endDrag()
            }
            Log.i(TAG, "Gesture: Selection Ended (Mouse Up)")
        }

        scope.launch(Dispatchers.Main) {
            delay(350)
            if (!isSelecting) {
                _activeGesture.value = ActiveGesture.NONE
            }
        }
    }

    fun triggerManualClick(button: String) {
        freezeCursorMotion(250L)
        hapticManager.vibrateClick()
        udpClient.sendPacket(ControlPacket.Click(button = button, action = "click"))
    }
}
