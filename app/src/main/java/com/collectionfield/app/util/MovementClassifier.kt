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

        // Asymmetric on purpose. Requiring three consecutive samples in both
        // directions meant a collector setting off stayed classified as stopped for
        // three more fixes, and the dashboard reported them parked while they were
        // already down the road. Starting to move is reported the moment the speed
        // says so; deciding someone has *stopped* still waits for confirmation,
        // because that is the direction where a single noisy sample does damage.
        mode = if (next != TrackingMode.STOPPED) {
            candidate = next
            next
        } else if (streak >= STOP_CONFIRMATIONS) {
            candidate
        } else {
            mode
        }
        return mode
    }

    private companion object {
        const val STOP_CONFIRMATIONS = 3
    }
}
