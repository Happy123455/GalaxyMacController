package com.galaxymac.controller.sensors

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Speed-adaptive low-pass filter for 2D angular velocity (a "1€ filter" variant).
 *
 * Near standstill the cutoff drops to [minCutoffHz], which strongly suppresses
 * hand tremor (8–12 Hz) so the cursor can be parked on tiny targets. As the hand
 * speeds up the cutoff rises by [betaHzPerRadS] per rad/s, so fast sweeps have
 * almost no added latency.
 */
class PointerFilter(
    private val minCutoffHz: Float = 1.6f,
    private val betaHzPerRadS: Float = 9.0f,
    private val speedCutoffHz: Float = 5.0f
) {
    var x = 0f
        private set
    var y = 0f
        private set

    /** Smoothed speed (rad/s) used to drive the cutoff and the pointer gain curve. */
    var speed = 0f
        private set

    private var initialized = false

    fun reset() {
        x = 0f
        y = 0f
        speed = 0f
        initialized = false
    }

    fun filter(rawX: Float, rawY: Float, dt: Float) {
        if (!initialized) {
            x = rawX
            y = rawY
            speed = sqrt(rawX * rawX + rawY * rawY)
            initialized = true
            return
        }
        val rawSpeed = sqrt(rawX * rawX + rawY * rawY)
        speed += alpha(speedCutoffHz, dt) * (rawSpeed - speed)

        val a = alpha(minCutoffHz + betaHzPerRadS * speed, dt)
        x += a * (rawX - x)
        y += a * (rawY - y)
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return dt / (tau + dt)
    }
}
