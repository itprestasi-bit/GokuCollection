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
 * There is no heartbeat back-off any more. It existed to spare data on a parked
 * phone, and it meant the dashboard could not tell a collector standing still from
 * a collector whose phone had died until minutes had passed. Every fix is now
 * pushed; a parked one repeats the same coordinates, so it costs bandwidth and
 * nothing else.
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

    /**
     * @param held true when the refiner repeated the previous position because this
     *   fix was inside the noise radius — the phone has not demonstrably moved.
     */
    fun evaluate(
        lat: Double,
        lng: Double,
        nowMs: Long,
        held: Boolean = false,
        speedMps: Float = 0f,
    ): Decision {
        val prevLat = lastSentLat
        val prevLng = lastSentLng

        // First fix of the shift always goes out — the map needs a starting point.
        if (prevLat == null || prevLng == null) {
            accept(lat, lng, nowMs, trail = true)
            return Decision(sendLive = true, recordTrail = true, reason = Reason.MOVED)
        }

        val sinceSendMs = nowMs - lastSentAtMs
        val sinceTrailMs = nowMs - lastTrailAtMs

        // The live feed goes out on every fix now, parked or not; the decision here
        // is only how to label it.
        //
        // Deciding *whether* to transmit was a data-cost optimisation, and it kept
        // buying trouble: every rule for "is this worth sending" was another way to
        // classify a working collector as idle, and a phone that had legitimately
        // gone quiet was indistinguishable from one that had died. Suppression is
        // not what keeps the marker still — the jitter hold is. A held fix carries
        // the previous coordinates unchanged, so sending it refreshes "last seen"
        // without moving anything on the map.
        if (sinceSendMs >= MIN_SEND_INTERVAL_MS) {
            if (held) {
                // Same position, fresh timestamp: alive, not moving.
                val recordTrail = sinceTrailMs >= TRAIL_STATIONARY_INTERVAL_MS
                accept(lat, lng, nowMs, trail = recordTrail)
                return Decision(sendLive = true, recordTrail = recordTrail, reason = Reason.HEARTBEAT)
            }
            // Real movement. The live feed takes every one of these; the trail is
            // still time-throttled, because a vehicle at speed clears 10 m every
            // couple of seconds and the trail only needs enough points to draw a
            // recognisable route later.
            val recordTrail = sinceTrailMs >= TRAIL_MOVING_INTERVAL_MS
            accept(lat, lng, nowMs, trail = recordTrail)
            return Decision(sendLive = true, recordTrail = recordTrail, reason = Reason.MOVED)
        }

        // Less than the minimum interval since the last push: nothing to add yet.
        return Decision(sendLive = false, recordTrail = false, reason = Reason.SUPPRESSED)
    }

    private fun accept(lat: Double, lng: Double, nowMs: Long, trail: Boolean) {
        lastSentLat = lat
        lastSentLng = lng
        lastSentAtMs = nowMs
        if (trail) lastTrailAtMs = nowMs
    }

    companion object {
        /**
         * Floor between live pushes while moving. Matches the GPS interval, so the
         * marker is refreshed as often as the phone has something new to say and
         * no more often than that.
         */
        private const val MIN_SEND_INTERVAL_MS = 2_000L


        // Trail cadence. 30 s meant a point every 330 m at 40 km/h — enough to prove
        // a route was driven, not enough to see how it was driven. 10 s draws the
        // turns, which is what makes a replay worth opening.
        private const val TRAIL_MOVING_INTERVAL_MS = 10_000L
        private const val TRAIL_STATIONARY_INTERVAL_MS = 60_000L
    }
}
