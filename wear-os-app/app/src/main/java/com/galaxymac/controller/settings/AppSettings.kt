package com.galaxymac.controller.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("galaxy_mac_prefs", Context.MODE_PRIVATE)

    val vibrationEnabled = MutableStateFlow(prefs.getBoolean(KEY_VIBRATION, false))
    val wristRollScrollEnabled = MutableStateFlow(prefs.getBoolean(KEY_WRIST_ROLL, true)) // Default TRUE per user request!
    val useTriplePinchForSelection = MutableStateFlow(prefs.getBoolean(KEY_TRIPLE_PINCH, true))
    val gestureClicksEnabled = MutableStateFlow(prefs.getBoolean(KEY_GESTURE_CLICKS, true))

    val pinchThreshold = MutableStateFlow(prefs.getFloat(KEY_PINCH_THRESH, 3.8f))
    val fistThreshold = MutableStateFlow(prefs.getFloat(KEY_FIST_THRESH, 4.0f))
    val verticalAxisMode = MutableStateFlow(prefs.getInt(KEY_VERTICAL_AXIS, 1))

    // Dual-speed sensitivity: Normal vs "About to Click" (Aiming Mode)
    val normalSpeed = MutableStateFlow(prefs.getFloat(KEY_NORMAL_SPEED, 52.0f))
    val aimingSpeed = MutableStateFlow(prefs.getFloat(KEY_AIMING_SPEED, 10.0f)) // Slowdown for small buttons!
    val horizontalSensitivity = MutableStateFlow(prefs.getFloat(KEY_HORIZ_SENS, 1.0f))
    val verticalSensitivity = MutableStateFlow(prefs.getFloat(KEY_VERT_SENS, 1.0f))

    fun setGestureClicksEnabled(enabled: Boolean) {
        gestureClicksEnabled.value = enabled
        prefs.edit().putBoolean(KEY_GESTURE_CLICKS, enabled).apply()
    }

    fun setVibrationEnabled(enabled: Boolean) {
        vibrationEnabled.value = enabled
        prefs.edit().putBoolean(KEY_VIBRATION, enabled).apply()
    }

    fun setWristRollScrollEnabled(enabled: Boolean) {
        wristRollScrollEnabled.value = enabled
        prefs.edit().putBoolean(KEY_WRIST_ROLL, enabled).apply()
    }

    fun setUseTriplePinch(enabled: Boolean) {
        useTriplePinchForSelection.value = enabled
        prefs.edit().putBoolean(KEY_TRIPLE_PINCH, enabled).apply()
    }

    fun setPinchThreshold(threshold: Float) {
        pinchThreshold.value = threshold
        prefs.edit().putFloat(KEY_PINCH_THRESH, threshold).apply()
    }

    fun setFistThreshold(threshold: Float) {
        fistThreshold.value = threshold
        prefs.edit().putFloat(KEY_FIST_THRESH, threshold).apply()
    }

    fun setVerticalAxisMode(mode: Int) {
        verticalAxisMode.value = mode
        prefs.edit().putInt(KEY_VERTICAL_AXIS, mode).apply()
    }

    fun cycleVerticalAxis() {
        val next = (verticalAxisMode.value + 1) % 4
        setVerticalAxisMode(next)
    }

    fun cycleNormalSpeed() {
        val next = when (normalSpeed.value) {
            35.0f -> 52.0f
            52.0f -> 70.0f
            else -> 35.0f
        }
        normalSpeed.value = next
        prefs.edit().putFloat(KEY_NORMAL_SPEED, next).apply()
    }

    fun cycleAimingSpeed() {
        val next = when (aimingSpeed.value) {
            6.0f -> 10.0f
            10.0f -> 16.0f
            else -> 6.0f
        }
        aimingSpeed.value = next
        prefs.edit().putFloat(KEY_AIMING_SPEED, next).apply()
    }

    fun cycleHorizontalSensitivity() {
        val next = when (horizontalSensitivity.value) {
            0.75f -> 1.0f
            1.0f -> 1.35f
            else -> 0.75f
        }
        horizontalSensitivity.value = next
        prefs.edit().putFloat(KEY_HORIZ_SENS, next).apply()
    }

    fun cycleVerticalSensitivity() {
        val next = when (verticalSensitivity.value) {
            0.75f -> 1.0f
            1.0f -> 1.35f
            else -> 0.75f
        }
        verticalSensitivity.value = next
        prefs.edit().putFloat(KEY_VERT_SENS, next).apply()
    }

    companion object {
        private const val KEY_VIBRATION = "pref_vibration"
        private const val KEY_WRIST_ROLL = "pref_wrist_roll"
        private const val KEY_GESTURE_CLICKS = "pref_gesture_clicks"
        private const val KEY_TRIPLE_PINCH = "pref_triple_pinch"
        private const val KEY_PINCH_THRESH = "pref_pinch_thresh"
        private const val KEY_FIST_THRESH = "pref_fist_thresh"
        private const val KEY_VERTICAL_AXIS = "pref_vertical_axis"
        private const val KEY_NORMAL_SPEED = "pref_normal_speed"
        private const val KEY_AIMING_SPEED = "pref_aiming_speed"
        private const val KEY_HORIZ_SENS = "pref_horiz_sens"
        private const val KEY_VERT_SENS = "pref_vert_sens"
    }
}
