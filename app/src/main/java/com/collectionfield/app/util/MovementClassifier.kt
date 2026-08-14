package com.collectionfield.app.util

import com.collectionfield.app.domain.TrackingMode

class MovementClassifier {
    private var candidate: TrackingMode = TrackingMode.MOVING
    private var streak = 0
    var mode: TrackingMode = TrackingMode.MOVING
        private set

    fun accept(speedMps: Float): TrackingMode {
        val next = when {
            speedMps >= 2.0f -> TrackingMode.MOVING
            speedMps >= 0.5f -> TrackingMode.SLOW
            else -> TrackingMode.STOPPED
        }

        if (next == candidate) streak++ else {
            candidate = next
            streak = 1
        }

        if (streak >= 3) mode = candidate
        return mode
    }
}
