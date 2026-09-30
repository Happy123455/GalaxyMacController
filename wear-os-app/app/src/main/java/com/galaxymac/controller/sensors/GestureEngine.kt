package com.galaxymac.controller.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
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
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    // All sensor processing and gesture state lives on this thread, off the UI thread.
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var isRunning = false

    private val _activeGesture = MutableStateFlow(ActiveGesture.NONE)
    val activeGesture: StateFlow<ActiveGesture> = _activeGesture

    private val _isAirMouseActive = MutableStateFlow(true)
    val isAirMouseActive: StateFlow<Boolean> = _isAirMouseActive

    val isAimingMode = MutableStateFlow(false)

    // ML Pattern Classifier instance
    val classifier = PinchPatternClassifier()

    // Live sensor values for telemetry (throttled to ~20 Hz so the UI isn't flooded)
    val liveGx = MutableStateFlow(0.0f)
    val liveGy = MutableStateFlow(0.0f)
    val liveGz = MutableStateFlow(0.0f)
    val liveAcc = MutableStateFlow(0.0f)
    private var lastTelemetryMs = 0L
    private var lastAccTelemetryMs = 0L

    // Calibration states
    @Volatile var isCalibratingPinch: Boolean = false
    @Volatile var isCalibratingFist: Boolean = false
    val calibrationStatus = MutableStateFlow("Ready")

    // Cursor freeze (locks cursor during bezel scroll and pinches so clicks land where you aimed)
    @Volatile private var motionFreezeUntil: Long = 0L

    // ---------------- Pointer state ----------------
    private val pointerFilter = PointerFilter()
    private var lastGyroTsNs = 0L
    private var biasX = 0f
    private var biasY = 0f
    private var biasZ = 0f
    private var stillSinceNs = 0L
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var lastFlushNs = 0L
    private var bleResidualX = 0f
    private var bleResidualY = 0f
    /** Slow average of rotation speed, used to reject "taps" that are really arm swings. */
    private var armSpeedSlow = 0f

    // Gravity ("up" in device coordinates) for tilt compensation and wrist-twist scrolling
    private val up = floatArrayOf(0f, 0f, 1f)
    private var hasGravity = false

    // ---------------- Pinch state ----------------
    private val accLowPass = FloatArray(3)
    private var lastAccTsNs = 0L
    private var accSampleCounter = 0
    private var pinchCounter: Int = 0
    private var lastSinglePinchTime: Long = 0L
    private var lastPinchAcceptedTime: Long = 0L
    private var firstPinchPeak = 0f
    private val resolvePinchRunnable = Runnable { resolveMultiPinch() }
    private val resetTrainingCounterRunnable = Runnable { pinchCounter = 0 }

    // Real-Time Gesture Review & Inspection State
    val lastReviewedGesture = MutableStateFlow("None")
    val lastReviewedCount = MutableStateFlow(0)
    val lastReviewedPeak = MutableStateFlow(0.0f)
    val lastReviewedInterval = MutableStateFlow(0L)
    val isReviewReady = MutableStateFlow(false)

    // Pulse tracking (sensor timestamps, ms)
    private var pulseStartTime: Long = 0L
    private var isPulseActive: Boolean = false
    private var maxPulseMagnitude: Float = 0.0f
    private var lastGestureTime: Long = 0L
    private var lastShakeTime: Long = 0L

    @Volatile var isSelecting: Boolean = false
        private set

    // Wrist twist (roll) scrolling
    @Volatile private var calibratedRollAngle: Float = 0.0f
    private var isRollCalibrated: Boolean = false
    @Volatile private var currentRollAngle: Float = 0.0f
    private var lastScrollSentTime: Long = 0

    private companion object {
        const val SENSOR_PERIOD_US = 5_000          // 200 Hz gyro + accelerometer
        const val GRAVITY_PERIOD_US = 10_000        // 100 Hz
        const val FLUSH_INTERVAL_NS = 8_000_000L    // send cursor updates at ~125 Hz
        const val PX_PER_RAD_PER_SPEED_UNIT = 62.5f // keeps the old speed-setting scale (1 unit = 1 px per 16 ms per rad/s)

        // Pointer acceleration curve: below SLOW the aiming gain applies, above FAST the normal gain
        const val GAIN_SLOW_RAD_S = 0.10f
        const val GAIN_FAST_RAD_S = 1.00f
        const val AIMING_INDICATOR_RAD_S = 0.20f
        const val NOISE_FLOOR_RAD_S = 0.006f

        // Gyro bias tracking (TYPE_GYROSCOPE is already calibrated; this only removes residual drift)
        const val STILL_RAD_S = 0.035f
        const val STILL_TIME_NS = 400_000_000L
        const val BIAS_RATE = 0.004f

        // Pinch detection
        const val ACC_HIGH_PASS_HZ = 4.0f
        const val MAX_ARM_SPEED_FOR_TAP = 2.2f      // rad/s; faster than this is arm motion, not a pinch
        const val MIN_INTER_PINCH_INTERVAL_MS = 140L // physical debounce against flesh/strap recoil
        const val MAX_PINCH_PULSE_MS = 110L
        const val SHAKE_THRESHOLD = 17.5f

        const val WRIST_ROLL_DEADZONE_DEG = 14.0f
        const val WRIST_ROLL_RECENTER_DEG = 8.0f
    }

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
        if (isRunning) return
        isRunning = true
        val thread = HandlerThread("GestureSensors", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        val handler = Handler(thread.looper)
        sensorThread = thread
        sensorHandler = handler

        lastGyroTsNs = 0L
        lastAccTsNs = 0L
        pointerFilter.reset()

        gyroSensor?.let { sensorManager.registerListener(this, it, SENSOR_PERIOD_US, handler) }
        linearAccSensor?.let { sensorManager.registerListener(this, it, SENSOR_PERIOD_US, handler) }
        gravitySensor?.let { sensorManager.registerListener(this, it, GRAVITY_PERIOD_US, handler) }
        Log.i(TAG, "Gesture Engine started (gravity sensor: ${gravitySensor != null}).")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        sensorManager.unregisterListener(this)
        sensorHandler?.removeCallbacksAndMessages(null)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        pinchCounter = 0
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
            Sensor.TYPE_GRAVITY -> handleGravity(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** BLE HID is only a fallback: sending over both links would move/click the Mac twice. */
    private fun bleActive(): Boolean = bleHidManager.isConnected.value && !udpClient.isLinked()

    // =====================================================================
    // Pointer
    // =====================================================================

    /**
     * Integrates angular velocity over the real sample interval and maps it through a
     * smooth pointer-acceleration curve: slow, deliberate motion gets the precise
     * "aiming" gain, fast sweeps get the normal gain, with no hard switch in between.
     */
    private fun handleGyroscope(event: SensorEvent) {
        val tsNs = event.timestamp
        val prevTs = lastGyroTsNs
        lastGyroTsNs = tsNs
        if (prevTs == 0L) return
        val dt = ((tsNs - prevTs) / 1e9f).coerceIn(0.0005f, 0.05f)

        // 1. Remove residual gyro bias while the hand is perfectly still
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]
        val cx = gx - biasX
        val cy = gy - biasY
        val cz = gz - biasZ
        val mag = sqrt(cx * cx + cy * cy + cz * cz)
        if (mag < STILL_RAD_S) {
            if (stillSinceNs == 0L) stillSinceNs = tsNs
            if (tsNs - stillSinceNs > STILL_TIME_NS) {
                biasX += BIAS_RATE * (gx - biasX)
                biasY += BIAS_RATE * (gy - biasY)
                biasZ += BIAS_RATE * (gz - biasZ)
            }
        } else {
            stillSinceNs = 0L
        }
        armSpeedSlow += (dt / (0.15f + dt)) * (mag - armSpeedSlow)

        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - lastTelemetryMs > 50) {
            lastTelemetryMs = nowMs
            liveGx.value = cx
            liveGy.value = cy
            liveGz.value = cz
        }

        if (!_isAirMouseActive.value) return

        // 2. Map body rotation to screen axes
        val (rawX, rawY) = mapToScreenAxes(cx, cy, cz)

        // 3. Speed-adaptive smoothing (kills tremor when slow, no lag when fast)
        pointerFilter.filter(rawX, rawY, dt)

        if (nowMs < motionFreezeUntil) {
            pendingDx = 0f
            pendingDy = 0f
            return
        }

        val speed = pointerFilter.speed
        val aiming = speed < AIMING_INDICATOR_RAD_S
        if (isAimingMode.value != aiming) isAimingMode.value = aiming

        // 4. Remove the sensor noise floor without a hard deadzone that eats fine movements
        var fx = pointerFilter.x
        var fy = pointerFilter.y
        val fMag = sqrt(fx * fx + fy * fy)
        if (fMag <= NOISE_FLOOR_RAD_S) {
            fx = 0f
            fy = 0f
        } else {
            val scale = (fMag - NOISE_FLOOR_RAD_S) / fMag
            fx *= scale
            fy *= scale
        }

        // 5. Pointer acceleration curve (smoothstep between aiming and normal gain)
        val lowGain = appSettings.aimingSpeed.value * PX_PER_RAD_PER_SPEED_UNIT
        val highGain = appSettings.normalSpeed.value * PX_PER_RAD_PER_SPEED_UNIT
        val t = ((speed - GAIN_SLOW_RAD_S) / (GAIN_FAST_RAD_S - GAIN_SLOW_RAD_S)).coerceIn(0f, 1f)
        val gain = lowGain + (highGain - lowGain) * (t * t * (3f - 2f * t))

        pendingDx += fx * dt * gain * appSettings.horizontalSensitivity.value
        pendingDy += fy * dt * gain * appSettings.verticalSensitivity.value

        // 6. Send at a steady rate; sub-pixel remainders keep accumulating
        if (tsNs - lastFlushNs >= FLUSH_INTERVAL_NS) {
            lastFlushNs = tsNs
            flushPointer()
        }
    }

    private fun flushPointer() {
        val dx = pendingDx
        val dy = pendingDy
        if (abs(dx) < 0.01f && abs(dy) < 0.01f) return
        pendingDx = 0f
        pendingDy = 0f

        udpClient.sendPacket(ControlPacket.Move(dx, dy))

        if (bleActive()) {
            bleResidualX += dx
            bleResidualY += dy
            val bx = bleResidualX.roundToInt().coerceIn(-127, 127)
            val by = bleResidualY.roundToInt().coerceIn(-127, 127)
            if (bx != 0 || by != 0) {
                bleResidualX -= bx
                bleResidualY -= by
                bleHidManager.sendMouseMove(bx.toByte(), by.toByte())
            }
        }
    }

    /**
     * Returns (horizontal, vertical) angular velocity in "screen" terms.
     *
     * With tilt compensation, horizontal motion is rotation around the real-world vertical
     * and vertical motion is rotation around the horizontal axis across the forearm, so
     * aiming stays straight even when the wrist is turned (e.g. palm facing sideways).
     */
    private fun mapToScreenAxes(cx: Float, cy: Float, cz: Float): Pair<Float, Float> {
        val mode = appSettings.verticalAxisMode.value
        val legacyY = when (mode) {
            0 -> -cx
            1 -> -cy
            2 -> cx
            3 -> cy
            else -> -cy
        }
        if (!hasGravity || !appSettings.tiltCompensation.value) {
            return Pair(-cz, legacyY)
        }

        // Modes 1/3 pitch around device Y, so the forearm runs along device X (modes 0/2: along Y).
        // Pitch axis = up × forearm (forearm on X) or forearm × up (forearm on Y);
        // both reduce to the legacy +Y / +X axis when the watch face points straight up.
        val forearmAlongX = mode == 1 || mode == 3
        var px = if (forearmAlongX) 0f else up[2]
        var py = if (forearmAlongX) up[2] else 0f
        var pz = if (forearmAlongX) -up[1] else -up[0]
        val pLen = sqrt(px * px + py * py + pz * pz)
        if (pLen < 0.35f) {
            // Forearm is pointing almost straight up/down: pitch axis is undefined, fall back
            return Pair(-cz, legacyY)
        }
        px /= pLen; py /= pLen; pz /= pLen

        val yaw = cx * up[0] + cy * up[1] + cz * up[2]
        val pitch = cx * px + cy * py + cz * pz
        val sign = if (mode == 0 || mode == 1) -1f else 1f
        return Pair(-yaw, sign * pitch)
    }

    // =====================================================================
    // Gravity: tilt compensation + wrist twist scrolling
    // =====================================================================

    private fun handleGravity(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val norm = sqrt(x * x + y * y + z * z)
        if (norm < 1f) return
        up[0] = x / norm
        up[1] = y / norm
        up[2] = z / norm
        hasGravity = true

        handleWristRoll(x, y, z)
    }

    /**
     * Wrist twist = rotation around the forearm. This is deliberately a different axis
     * from vertical pointing, so aiming up/down never triggers scrolling.
     */
    private fun handleWristRoll(x: Float, y: Float, z: Float) {
        val forearmAlongX = appSettings.verticalAxisMode.value.let { it == 1 || it == 3 }
        val side = if (forearmAlongX) y else -x
        // Arm pointing near vertical: twist angle is meaningless
        if (sqrt(side * side + z * z) < 3.0f) return
        val rollDeg = Math.toDegrees(atan2(side, z).toDouble()).toFloat()
        currentRollAngle = rollDeg

        if (!appSettings.wristRollScrollEnabled.value) {
            if (_activeGesture.value == ActiveGesture.WRIST_ROLL_SCROLLING) {
                _activeGesture.value = ActiveGesture.NONE
            }
            return
        }

        if (!isRollCalibrated) {
            calibratedRollAngle = rollDeg
            isRollCalibrated = true
        }

        val deltaRoll = currentRollAngle - calibratedRollAngle
        val now = SystemClock.uptimeMillis()

        // Re-center neutral baseline when the wrist rests near center
        if (abs(deltaRoll) <= WRIST_ROLL_RECENTER_DEG) {
            calibratedRollAngle = calibratedRollAngle * 0.98f + currentRollAngle * 0.02f
            if (_activeGesture.value == ActiveGesture.WRIST_ROLL_SCROLLING) {
                _activeGesture.value = ActiveGesture.NONE
            }
            return
        }

        if (abs(deltaRoll) > WRIST_ROLL_DEADZONE_DEG) {
            if (now - lastScrollSentTime >= 40) {
                lastScrollSentTime = now

                // Clockwise twist scrolls DOWN, anti-clockwise scrolls UP (invertible in settings).
                // Quadratic ramp: small twists scroll gently, big twists scroll fast.
                val excess = (abs(deltaRoll) - WRIST_ROLL_DEADZONE_DEG).coerceAtMost(30f)
                val magnitude = (excess * 1.2f + excess * excess * 0.09f).coerceAtMost(90f)
                var scrollY = if (deltaRoll > 0) -magnitude else magnitude
                if (appSettings.invertRollScroll.value) scrollY = -scrollY

                _activeGesture.value = ActiveGesture.WRIST_ROLL_SCROLLING
                udpClient.sendPacket(ControlPacket.Scroll(scrollX = 0.0f, scrollY = scrollY))
                if (bleActive()) {
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

    // =====================================================================
    // Pinch / fist / shake detection
    // =====================================================================

    /**
     * Pinch taps are short, high-frequency transients. Arm movement is low frequency, so the
     * acceleration is high-pass filtered first and taps during fast arm swings are rejected.
     */
    private fun handleLinearAcceleration(event: SensorEvent) {
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        val tsNs = event.timestamp
        val dt = if (lastAccTsNs == 0L) 0.005f else ((tsNs - lastAccTsNs) / 1e9f).coerceIn(0.0005f, 0.05f)
        lastAccTsNs = tsNs
        val rc = 1f / (2f * Math.PI.toFloat() * ACC_HIGH_PASS_HZ)
        val a = dt / (rc + dt)
        accLowPass[0] += a * (ax - accLowPass[0])
        accLowPass[1] += a * (ay - accLowPass[1])
        accLowPass[2] += a * (az - accLowPass[2])
        val hx = ax - accLowPass[0]
        val hy = ay - accLowPass[1]
        val hz = az - accLowPass[2]

        val rawMagnitude = sqrt(ax * ax + ay * ay + az * az)
        val magnitude = sqrt(hx * hx + hy * hy + hz * hz)

        // Waveform buffer at 100 Hz: 60 samples = 600 ms, matching the Mac studio window
        accSampleCounter++
        if (accSampleCounter % 2 == 0) {
            classifier.pushWaveformSample(magnitude)
        }
        val uiNow = SystemClock.uptimeMillis()
        if (uiNow - lastAccTelemetryMs > 50) {
            lastAccTelemetryMs = uiNow
            liveAcc.value = magnitude
        }

        val now = tsNs / 1_000_000L

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
        if (rawMagnitude >= SHAKE_THRESHOLD && !isPulseActive) {
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

        // Early freeze: stop the cursor as soon as a tap starts building, before it crosses
        // the threshold, so the tap's own jolt doesn't nudge the pointer off target.
        if (magnitude > pinchThresh * 0.6f && armSpeedSlow < MAX_ARM_SPEED_FOR_TAP) {
            freezeCursorMotion(150L)
        }

        // 2. Pulse Start
        if (magnitude > pinchThresh) {
            if (!isPulseActive) {
                if (armSpeedSlow > MAX_ARM_SPEED_FOR_TAP) return // arm swing, not a pinch
                isPulseActive = true
                pulseStartTime = now
                maxPulseMagnitude = magnitude
                freezeCursorMotion(240L)
            } else if (magnitude > maxPulseMagnitude) {
                maxPulseMagnitude = magnitude
            }
        }

        // 3. Pulse Finish (hysteresis at 50%) & Duration Separation with Physical Debounce
        if (isPulseActive && (magnitude <= pinchThresh * 0.5f || (now - pulseStartTime) > 220L)) {
            isPulseActive = false
            val pulseDuration = now - pulseStartTime

            if (pulseDuration < MAX_PINCH_PULSE_MS) {
                val timeSinceLastPinch = now - lastPinchAcceptedTime
                if (timeSinceLastPinch < MIN_INTER_PINCH_INTERVAL_MS) {
                    Log.d(TAG, "Suppressed recoil bounce: $timeSinceLastPinch ms")
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
     * Multi-Pinch Dispatcher with Ground-Truth Inspection. Runs on the sensor thread.
     */
    private fun onPinchDetected(now: Long, peak: Float, duration: Long) {
        val handler = sensorHandler ?: return
        val phase = classifier.trainingPhase.value

        // Guided Training: Phase 1 (Single Pinch)
        if (phase == TrainingPhase.TRAINING_SINGLE_PINCH) {
            hapticManager.vibrateClick()
            val snapshot = classifier.getRecentWaveformSnapshot()
            udpClient.sendPacket(ControlPacket.WaveformEvent("Single Pinch", 1, peak, 0L, snapshot))
            val done = classifier.recordSinglePinchTrial(peak, duration)
            if (done) hapticManager.vibrateDoubleFistRightClick()
            return
        }

        pinchCounter++
        handler.removeCallbacks(resolvePinchRunnable)
        handler.removeCallbacks(resetTrainingCounterRunnable)
        hapticManager.vibrateClick()

        val interval = if (lastSinglePinchTime > 0) now - lastSinglePinchTime else 220L
        lastSinglePinchTime = now
        if (pinchCounter == 1) firstPinchPeak = peak

        // Guided Training: Phase 2 (Double Pinch)
        if (phase == TrainingPhase.TRAINING_DOUBLE_PINCH) {
            if (pinchCounter == 2) {
                pinchCounter = 0
                val snapshot = classifier.getRecentWaveformSnapshot()
                udpClient.sendPacket(ControlPacket.WaveformEvent("Double Pinch", 2, peak, interval, snapshot))
                val done = classifier.recordDoublePinchTrial(interval)
                if (done) hapticManager.vibrateDoubleFistRightClick()
            } else {
                handler.postDelayed(resetTrainingCounterRunnable, 600)
            }
            return
        }

        // Guided Training: Phase 3 (Triple Pinch)
        if (phase == TrainingPhase.TRAINING_TRIPLE_PINCH) {
            if (pinchCounter == 3) {
                pinchCounter = 0
                val snapshot = classifier.getRecentWaveformSnapshot()
                udpClient.sendPacket(ControlPacket.WaveformEvent("Triple Pinch", 3, peak, interval, snapshot))
                val done = classifier.recordTriplePinchTrial(interval)
                if (done) {
                    appSettings.setPinchThreshold(classifier.getRecommendedThreshold())
                    appSettings.setMultiTapWindowMs(classifier.getRecommendedMultiTapWindow())
                    hapticManager.vibrateHoldEnd()
                }
            } else {
                handler.postDelayed(resetTrainingCounterRunnable, 600)
            }
            return
        }

        // Keep the cursor pinned for the whole multi-pinch sequence so the click lands on target
        val window = appSettings.multiTapWindowMs.value
        freezeCursorMotion(window + 120L)

        // Operational Mode: Immediate Triple Pinch -> RIGHT CLICK
        if (pinchCounter >= 3) {
            pinchCounter = 0
            publishReview("Triple Pinch -> Right Click", 3, peak, interval)
            triggerDoublePinchRightClick()
            return
        }

        // Wait to see whether more pinches follow
        lastReviewedPeak.value = peak
        lastReviewedInterval.value = interval
        handler.postDelayed(resolvePinchRunnable, window)
    }

    private fun resolveMultiPinch() {
        val count = pinchCounter
        pinchCounter = 0
        if (count == 0) return

        val gName = when (count) {
            1 -> "Single Pinch (Ignored)"
            2 -> "Double Pinch -> Left Click"
            else -> "Triple Pinch -> Right Click"
        }
        publishReview(gName, count, maxOf(firstPinchPeak, lastReviewedPeak.value), lastReviewedInterval.value)

        when (count) {
            1 -> Log.i(TAG, "Single pinch ignored (no click dispatched).")
            2 -> triggerPinchLeftClick()
            else -> triggerDoublePinchRightClick()
        }
    }

    private fun publishReview(name: String, count: Int, peak: Float, interval: Long) {
        lastReviewedGesture.value = name
        lastReviewedCount.value = count
        lastReviewedPeak.value = peak
        lastReviewedInterval.value = interval
        isReviewReady.value = true

        val snapshot = classifier.getRecentWaveformSnapshot()
        udpClient.sendPacket(ControlPacket.GestureReview(name, count, peak, interval))
        udpClient.sendPacket(ControlPacket.WaveformEvent(name, count, peak, interval, snapshot))
    }

    /**
     * Active human-in-the-loop review feedback
     */
    fun applyReviewFeedback(actualCount: Int) {
        val detected = lastReviewedCount.value
        Log.i(TAG, "User Review Feedback: actual=$actualCount, detected=$detected")

        if (actualCount < detected) {
            // Watch saw more pinches than were made -> recoil / noise, raise threshold slightly
            val cur = appSettings.pinchThreshold.value
            appSettings.setPinchThreshold((cur + 0.3f).coerceAtMost(6.0f))
        } else if (actualCount > detected) {
            // Watch missed a pinch -> lower threshold slightly
            val cur = appSettings.pinchThreshold.value
            appSettings.setPinchThreshold((cur - 0.25f).coerceAtLeast(1.5f))
        }

        hapticManager.vibrateClick()
        isReviewReady.value = false
    }

    fun dismissReview() {
        isReviewReady.value = false
    }

    // =====================================================================
    // Bezel & actions
    // =====================================================================

    /**
     * Hardware Bezel rotation handler
     */
    fun handleBezelScroll(delta: Float) {
        freezeCursorMotion(450L)

        _activeGesture.value = ActiveGesture.BEZEL_SCROLLING
        hapticManager.vibrateScrollTick()

        val scrollAmount = -delta * 40.0f
        udpClient.sendPacket(ControlPacket.Scroll(scrollX = 0.0f, scrollY = scrollAmount))

        if (bleActive()) {
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
        freezeCursorMotion(200L)
        _activeGesture.value = ActiveGesture.PINCH_LEFT_CLICK

        if (appSettings.gestureClicksEnabled.value) {
            udpClient.sendPacket(ControlPacket.Click(button = "left", action = "click"))
            if (bleActive()) {
                bleHidManager.sendLeftClick()
            }
            Log.i(TAG, "Gesture: DOUBLE PINCH -> Left Click")
        } else {
            Log.i(TAG, "Gesture Test: DOUBLE PINCH -> Left Click (Clicks disabled)")
        }

        scope.launch(Dispatchers.Main) {
            delay(200)
            if (_activeGesture.value == ActiveGesture.PINCH_LEFT_CLICK) {
                _activeGesture.value = ActiveGesture.NONE
            }
        }
    }

    fun triggerDoublePinchRightClick() {
        freezeCursorMotion(250L)
        _activeGesture.value = ActiveGesture.DOUBLE_PINCH_RIGHT_CLICK
        hapticManager.vibrateDoubleFistRightClick()

        if (appSettings.gestureClicksEnabled.value) {
            udpClient.sendPacket(ControlPacket.Click(button = "right", action = "click"))
            if (bleActive()) {
                bleHidManager.sendRightClick()
            }
            Log.i(TAG, "Gesture: TRIPLE PINCH -> Right Click")
        } else {
            Log.i(TAG, "Gesture Test: TRIPLE PINCH -> Right Click (Clicks disabled)")
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

    @Synchronized
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
            if (bleActive()) {
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

        // Always release, even in test mode, so a button can never stay stuck down
        udpClient.sendPacket(ControlPacket.Click(button = "left", action = "up"))
        if (bleActive()) {
            bleHidManager.endDrag()
        }
        Log.i(TAG, "Gesture: Selection Ended (Mouse Up)")

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
        if (bleActive()) {
            if (button == "right") bleHidManager.sendRightClick() else bleHidManager.sendLeftClick()
        }
    }
}
