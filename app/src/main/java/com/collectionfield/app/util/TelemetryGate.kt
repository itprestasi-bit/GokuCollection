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
 * Movement gate: nothing is sent unless the fix carried new information, or a
 * heartbeat is due so the dashboard can tell the phone is still alive rather than
 * dead. "New information" is decided upstream, by the refiner's jitter hold: a fix
 * inside the noise radius is returned as a repeat of the previous position, and a
 * repeat is never worth transmitting. Re-deriving that here from a distance
 * threshold was what capped the live feed at walking pace — 1.4 m/s takes seven
 * seconds to clear 10 m, so a walking collector's marker updated every 7 s no
 * matter what interval the GPS was asked for.
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

    /**
     * @param held true when the refiner repeated the previous position because this
     *   fix was inside the noise radius — the phone has not demonstrably moved.
     */
    fun evaluate(lat: Double, lng: Double, nowMs: Long, held: Boolean = false): Decision {
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

        // Genuine movement: the fix cleared the noise radius and the position on the
        // dashboard is out of date. The floor keeps a burst of fixes from turning
        // into a burst of writes without capping the cadence the app asks for.
        if (!held && movedM > 0.0 && sinceSendMs >= MIN_SEND_INTERVAL_MS) {
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
        /**
         * Floor between live pushes while moving. Matches the GPS interval, so the
         * marker is refreshed as often as the phone has something new to say and
         * no more often than that.
         */
        private const val MIN_SEND_INTERVAL_MS = 3_000L

        private const val HEARTBEAT_BASE_MS = 30_000L
        private const val HEARTBEAT_MAX_MS = 300_000L

        // Trail cadence. At 40 km/h, 30 s is a point roughly every 330 m — ample
        // for a route replay, and ~4.8k documents/day across four collectors on
        // ten-hour shifts, comfortably inside the daily write allowance.
        private const val TRAIL_MOVING_INTERVAL_MS = 30_000L
        private const val TRAIL_STATIONARY_INTERVAL_MS = 300_000L
    }
}
