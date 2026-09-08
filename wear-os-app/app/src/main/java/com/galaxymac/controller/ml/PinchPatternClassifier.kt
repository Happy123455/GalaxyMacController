package com.galaxymac.controller.ml

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.sqrt

enum class TrainingPhase {
    IDLE,
    TRAINING_SINGLE_PINCH,
    TRAINING_DOUBLE_PINCH,
    TRAINING_TRIPLE_PINCH,
    COMPLETED
}

class PinchPatternClassifier {
    private val TAG = "PinchClassifier"

    val trainingPhase = MutableStateFlow(TrainingPhase.IDLE)
    val currentSampleCount = MutableStateFlow(0)
    val maxSamplesPerPhase = 25 // 25 guided trials per gesture for maximum precision

    // Recorded samples for ML statistical feature extraction
    private val singlePinchPeaks = mutableListOf<Float>()
    private val singlePinchDurations = mutableListOf<Long>()
    private val doublePinchIntervals = mutableListOf<Long>()
    private val triplePinchIntervals = mutableListOf<Long>()

    // Learned Statistical Model Parameters
    var isModelTrained = MutableStateFlow(false)
    var trainedPeakMean: Float = 3.6f
    var trainedPeakStd: Float = 0.6f
    var trainedDoubleIntervalMean: Long = 240L
    var trainedDoubleIntervalStd: Long = 50L
    var trainedTripleIntervalMean: Long = 220L
    var trainedTripleIntervalStd: Long = 45L

    // Live Waveform Ring Buffer for drawing the real-time 📈📉 graph on watch face
    val waveformSize = 60
    val waveformBuffer = FloatArray(waveformSize) { 0.0f }
    var waveformHead = 0
    val waveformUpdateTrigger = MutableStateFlow(0L)

    fun pushWaveformSample(magnitude: Float) {
        waveformBuffer[waveformHead] = magnitude
        waveformHead = (waveformHead + 1) % waveformSize
        waveformUpdateTrigger.value = System.currentTimeMillis()
    }

    fun getRecentWaveformSnapshot(): List<Float> {
        val list = ArrayList<Float>(waveformSize)
        for (i in 0 until waveformSize) {
            val idx = (waveformHead + i) % waveformSize
            list.add(waveformBuffer[idx])
        }
        return list
    }

    fun startTraining() {
        singlePinchPeaks.clear()
        singlePinchDurations.clear()
        doublePinchIntervals.clear()
        triplePinchIntervals.clear()

        trainingPhase.value = TrainingPhase.TRAINING_SINGLE_PINCH
        currentSampleCount.value = 0
        Log.i(TAG, "Starting 15-trial ML Gesture Training: Phase 1 (Single Pinch)")
    }

    fun recordSinglePinchTrial(peak: Float, duration: Long): Boolean {
        if (trainingPhase.value != TrainingPhase.TRAINING_SINGLE_PINCH) return false

        singlePinchPeaks.add(peak)
        singlePinchDurations.add(duration)
        currentSampleCount.value = singlePinchPeaks.size

        if (singlePinchPeaks.size >= maxSamplesPerPhase) {
            // Transition to Phase 2: Double Pinch
            trainingPhase.value = TrainingPhase.TRAINING_DOUBLE_PINCH
            currentSampleCount.value = 0
            Log.i(TAG, "Phase 1 Complete! Transitioning to Phase 2 (Double Pinch)")
            return true
        }
        return false
    }

    fun recordDoublePinchTrial(interval: Long): Boolean {
        if (trainingPhase.value != TrainingPhase.TRAINING_DOUBLE_PINCH) return false

        doublePinchIntervals.add(interval)
        currentSampleCount.value = doublePinchIntervals.size

        if (doublePinchIntervals.size >= maxSamplesPerPhase) {
            // Transition to Phase 3: Triple Pinch
            trainingPhase.value = TrainingPhase.TRAINING_TRIPLE_PINCH
            currentSampleCount.value = 0
            Log.i(TAG, "Phase 2 Complete! Transitioning to Phase 3 (Triple Pinch)")
            return true
        }
        return false
    }

    fun recordTriplePinchTrial(interval: Long): Boolean {
        if (trainingPhase.value != TrainingPhase.TRAINING_TRIPLE_PINCH) return false

        triplePinchIntervals.add(interval)
        currentSampleCount.value = triplePinchIntervals.size

        if (triplePinchIntervals.size >= maxSamplesPerPhase) {
            // All 45 trials complete! Compute ML Model
            computeModelParameters()
            trainingPhase.value = TrainingPhase.COMPLETED
            Log.i(TAG, "Training complete! Model parameters fitted.")
            return true
        }
        return false
    }

    private fun computeModelParameters() {
        if (singlePinchPeaks.isNotEmpty()) {
            trainedPeakMean = singlePinchPeaks.average().toFloat()
            val variance = singlePinchPeaks.map { (it - trainedPeakMean) * (it - trainedPeakMean) }.average()
            trainedPeakStd = sqrt(variance).toFloat()
        }

        if (doublePinchIntervals.isNotEmpty()) {
            trainedDoubleIntervalMean = doublePinchIntervals.average().toLong()
            val variance = doublePinchIntervals.map { (it - trainedDoubleIntervalMean) * (it - trainedDoubleIntervalMean) }.average()
            trainedDoubleIntervalStd = sqrt(variance).toLong()
        }

        if (triplePinchIntervals.isNotEmpty()) {
            trainedTripleIntervalMean = triplePinchIntervals.average().toLong()
            val variance = triplePinchIntervals.map { (it - trainedTripleIntervalMean) * (it - trainedTripleIntervalMean) }.average()
            trainedTripleIntervalStd = sqrt(variance).toLong()
        }

        isModelTrained.value = true
        Log.i(TAG, "ML Model: Peak=%.2f ± %.2f, DoubleInt=%d ± %d, TripleInt=%d ± %d".format(
            trainedPeakMean, trainedPeakStd,
            trainedDoubleIntervalMean, trainedDoubleIntervalStd,
            trainedTripleIntervalMean, trainedTripleIntervalStd
        ))
    }

    fun getRecommendedThreshold(): Float {
        // Optimal decision boundary: Mean minus 1.25 standard deviations
        return (trainedPeakMean - (trainedPeakStd * 1.25f)).coerceAtLeast(2.0f)
    }

    fun getRecommendedMultiTapWindow(): Long {
        // Multi-tap window comfortably covering user's cadences: Mean + 2.0 standard deviations
        val cadence = maxOf(trainedDoubleIntervalMean, trainedTripleIntervalMean)
        val spread = maxOf(trainedDoubleIntervalStd, trainedTripleIntervalStd)
        return (cadence + (spread * 2L)).coerceIn(280L, 500L)
    }

    fun cancelTraining() {
        trainingPhase.value = TrainingPhase.IDLE
        currentSampleCount.value = 0
    }
}
