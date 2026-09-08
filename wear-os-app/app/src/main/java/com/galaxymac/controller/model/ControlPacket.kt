package com.galaxymac.controller.model

import org.json.JSONObject

sealed class ControlPacket {
    abstract fun toJson(): String

    data class Move(val dx: Float, val dy: Float) : ControlPacket() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "move")
            put("dx", dx.toDouble())
            put("dy", dy.toDouble())
        }.toString()
    }

    data class Click(val button: String, val action: String) : ControlPacket() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "click")
            put("button", button) // "left" or "right"
            put("action", action) // "click", "down", "up"
        }.toString()
    }

    data class Scroll(val scrollX: Float, val scrollY: Float) : ControlPacket() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "scroll")
            put("scrollX", scrollX.toDouble())
            put("scrollY", scrollY.toDouble())
        }.toString()
    }

    data class Discover(val deviceName: String) : ControlPacket() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "discover")
            put("device", deviceName)
        }.toString()
    }

    data class Ping(val timestamp: Long = System.currentTimeMillis()) : ControlPacket() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "ping")
            put("ts", timestamp)
        }.toString()
    }

    data class GestureReview(val gestureType: String, val count: Int, val peak: Float, val interval: Long) : ControlPacket() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "gesture_review")
            put("gesture", gestureType)
            put("count", count)
            put("peak", peak.toDouble())
            put("interval", interval)
        }.toString()
    }

    data class WaveformEvent(
        val gestureType: String,
        val count: Int,
        val peak: Float,
        val interval: Long,
        val samples: List<Float>
    ) : ControlPacket() {
        override fun toJson(): String = JSONObject().apply {
            put("type", "waveform_event")
            put("gesture", gestureType)
            put("count", count)
            put("peak", peak.toDouble())
            put("interval", interval)
            val jsonArray = org.json.JSONArray()
            samples.forEach { jsonArray.put(it.toDouble()) }
            put("samples", jsonArray)
        }.toString()
    }
}

enum class ConnectionStatus {
    DISCONNECTED,
    SEARCHING,
    CONNECTED
}

enum class ActiveGesture {
    NONE,
    PINCH_LEFT_CLICK,
    DOUBLE_PINCH_RIGHT_CLICK,
    TRIPLE_PINCH_SELECTION,
    FIST_SELECTION_STARTED,
    FIST_SELECTION_ACTIVE,
    FIST_SELECTION_ENDED,
    WRIST_ROLL_SCROLLING,
    BEZEL_SCROLLING
}
