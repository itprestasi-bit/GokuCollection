package com.collectionfield.app.util

/**
 * Decides, per accepted GPS fix, whether it is worth transmitting — and to which
 * of the two destinations.
 *
 * The two destinations are billed completely differently, which is the whole
 * reason this class exists:
 *
 *  - **Live feed** (Realtime Database) is billed by bandwidth. A small payload
 *    every few seconds is cheap, and it's what makes the admin map move
 *    smoothly. It should stay frequent.
 *  - **Durable trail** (Firestore) is billed *per document written*, against a
 *    20,000/day allowance shared by the whole project. This is the one that has
 *    to stay sparse. A collector parked at a customer for twenty minutes used to
 *    write ~80 documents describing the same parked spot.
 *
 * Movement gate: nothing is sent unless the collector has actually moved
 * [MIN_DISTANCE_M], or a heartbeat is due so the dashboard can tell the phone is
 * still alive rather than dead.
 *
 * Heartbeat back-off: a stationary phone starts at [HEARTBEAT_BASE_MS] and then
 * doubles up to [HEARTBEAT_MAX_MS]. A fixed 30-second heartbeat would still be
 * 40 transmissions across a 20-minute stop; backing off makes that 6 while the
 * "still alive" signal never goes quiet for more than a few minutes. Any real
 * movement resets it immediately.
 */
class TelemetryGate {

    data class Decision(
        /** Push to the live map feed. */
        val sendLive: Boolean,
        /** Append to the durable Firestore trail. */
        val recordTrail: Boolean,
        /** Why — surfaced in the foreground notification, useful in the field. */
        val reason: Reason,
    )

    enum class Reason { MOVED, HEARTBEAT, SUPPRESSED }

    private var lastSentLat: Double? = null
    private var lastSentLng: Double? = null
    private var lastSentAtMs = 0L
    private var lastTrailAtMs = 0L
    private var heartbeatIntervalMs = HEARTBEAT_BASE_MS

    fun evaluate(lat: Double, lng: Double, nowMs: Long): Decision {
        val prevLat = lastSentLat
        val prevLng = lastSentLng

        // First fix of the shift always goes out — the map needs a starting point.
        if (prevLat == null || prevLng == null) {
            accept(lat, lng, nowMs, trail = true)
            heartbeatIntervalMs = HEARTBEAT_BASE_MS
            return Decision(sendLive = true, recordTrail = true, reason = Reason.MOVED)
        }

        val movedM = GeoMath.distanceMeters(prevLat, prevLng, lat, lng)
        val sinceSendMs = nowMs - lastSentAtMs
        val sinceTrailMs = nowMs - lastTrailAtMs

        if (movedM >= MIN_DISTANCE_M) {
            // Real movement. The live feed takes every one of these; the trail is
            // still time-throttled, because a vehicle at speed clears 10 m every
            // couple of seconds and the trail only needs enough points to draw a
            // recognisable route later.
            val recordTrail = sinceTrailMs >= TRAIL_MOVING_INTERVAL_MS
            accept(lat, lng, nowMs, trail = recordTrail)
            heartbeatIntervalMs = HEARTBEAT_BASE_MS
            return Decision(sendLive = true, recordTrail = recordTrail, reason = Reason.MOVED)
        }

        if (sinceSendMs >= heartbeatIntervalMs) {
            // Stationary, but it's time to prove the phone is still on. The trail
            // gets a point far less often than the live feed does.
            val recordTrail = sinceTrailMs >= TRAIL_STATIONARY_INTERVAL_MS
            accept(lat, lng, nowMs, trail = recordTrail)
            heartbeatIntervalMs = (heartbeatIntervalMs * 2).coerceAtMost(HEARTBEAT_MAX_MS)
            return Decision(sendLive = true, recordTrail = recordTrail, reason = Reason.HEARTBEAT)
        }

        // Standing still and the heartbeat isn't due: send nothing at all.
        return Decision(sendLive = false, recordTrail = false, reason = Reason.SUPPRESSED)
    }

    private fun accept(lat: Double, lng: Double, nowMs: Long, trail: Boolean) {
        lastSentLat = lat
        lastSentLng = lng
        lastSentAtMs = nowMs
        if (trail) lastTrailAtMs = nowMs
    }

    /** Current stationary heartbeat spacing, for display. */
    val heartbeatSeconds: Int get() = (heartbeatIntervalMs / 1000).toInt()

    companion object {
        /** Movement below this is treated as staying put (matches the GPS request's own filter). */
        const val MIN_DISTANCE_M = 10.0

        private const val HEARTBEAT_BASE_MS = 30_000L
        private const val HEARTBEAT_MAX_MS = 300_000L

        // Trail cadence. At 40 km/h, 30 s is a point roughly every 330 m — ample
        // for a route replay, and ~4.8k documents/day across four collectors on
        // ten-hour shifts, comfortably inside the daily write allowance.
        private const val TRAIL_MOVING_INTERVAL_MS = 30_000L
        private const val TRAIL_STATIONARY_INTERVAL_MS = 300_000L
    }
}
