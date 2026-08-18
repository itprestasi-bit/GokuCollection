package com.collectionfield.app.util

import android.location.Location

/**
 * Cleans up the raw GPS stream from FusedLocationProvider before it's persisted or
 * broadcast to the live map. Stateful — one instance lives for the whole tracking
 * session (see LocationTrackingService), since teleport/jitter checks and the
 * Kalman filter both need the previous accepted fix to reason about the new one.
 *
 * Order of operations per fix: accuracy gate -> teleport reject -> jitter hold ->
 * Kalman position smoothing -> speed EMA. A fix can be dropped (refine() returns
 * null) at the first two stages; once accepted, it always produces an output.
 */
class LocationRefiner {
    private var kalmanLat: Double? = null
    private var kalmanLng: Double? = null
    private var kalmanVarianceM2 = -1.0
    private var lastKalmanAtMs = 0L

    private var lastAcceptedLat: Double? = null
    private var lastAcceptedLng: Double? = null
    private var lastAcceptedAtMs = 0L
    private var lastAnyAcceptAtMs = 0L
    private var lastAcceptedAccuracyM = 0f
    private var consecutiveRejects = 0
    private var smoothedSpeedMps = 0f

    /** Thin adapter over the platform type; the logic lives in [refine] below. */
    fun refine(location: Location, isStationary: Boolean): RefinedLocation? = refine(
        rawLat = location.latitude,
        rawLng = location.longitude,
        accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
        rawSpeedMps = if (location.hasSpeed() && location.speed >= 0f) location.speed else 0f,
        bearing = if (location.hasBearing()) location.bearing else null,
        timeMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
        isStationary = isStationary,
    )

    /**
     * The actual filter, on primitives rather than [Location].
     *
     * Split out so the behaviour can be unit-tested on the JVM: `Location` is a
     * framework class that returns stubs off-device, which would make any test of
     * the pipeline a test of the stub instead of the algorithm.
     */
    fun refine(
        rawLat: Double,
        rawLng: Double,
        accuracyM: Float,
        rawSpeedMps: Float,
        bearing: Float?,
        timeMs: Long,
        isStationary: Boolean,
    ): RefinedLocation? {
        val now = timeMs

        // 1. Accuracy gate.
        //
        // A coarse fix is rarely *wrong*; it is uninformative. If it lands within
        // its own error radius of where we already are, it is precisely what a
        // phone that has not moved would report — so publishing it would trade a
        // 12 m position for a 100 m one and shove the marker sideways on no
        // evidence. Hold the last good position instead and let the clock move on:
        // the dashboard still gets its heartbeat, without the marker inventing a
        // journey. (This is where the reported +-100 m came from. The old rule let
        // any fix through once nothing had been accepted for MAX_SILENCE_MS,
        // which while parked meant one cell-tower estimate every two minutes.)
        //
        // Only a coarse fix that *contradicts* the last known position by more
        // than its own error is worth taking, and then only when nothing better
        // has arrived for MAX_SILENCE_MS: at that point it is the sole evidence
        // the collector has gone somewhere, and rough beats confidently stale.
        val starved = lastAnyAcceptAtMs == 0L || now - lastAnyAcceptAtMs > MAX_SILENCE_MS
        if (accuracyM > MAX_ACCEPTABLE_ACCURACY_M) {
            val heldLat = lastAcceptedLat
            val heldLng = lastAcceptedLng
            if (heldLat != null && heldLng != null &&
                GeoMath.distanceMeters(heldLat, heldLng, rawLat, rawLng) <= accuracyM
            ) {
                // Not a rejection: the position stands, only the timestamp advances.
                // Accuracy is reported as that of the fix these coordinates actually
                // came from, since that is the measurement being repeated here.
                lastAcceptedAtMs = now
                lastAnyAcceptAtMs = now
                smoothedSpeedMps = 0f
                return RefinedLocation(
                    lat = heldLat,
                    lng = heldLng,
                    accuracyM = lastAcceptedAccuracyM,
                    speedMps = 0f,
                    bearing = bearing,
                    capturedAtMs = now,
                )
            }
            if (!starved) return null
        }

        // 2. Teleport rejection — reject a jump that implies an impossible speed,
        // unless it's happened too many times in a row (the anchor point itself
        // might be the bad one, so give up rejecting and resync to reality).
        val prevLat = lastAcceptedLat
        val prevLng = lastAcceptedLng
        if (prevLat != null && prevLng != null && lastAcceptedAtMs != 0L) {
            val elapsedS = (now - lastAcceptedAtMs) / 1000.0
            if (elapsedS > 0.05) {
                val distanceM = GeoMath.distanceMeters(prevLat, prevLng, rawLat, rawLng)
                val impliedSpeedMps = distanceM / elapsedS
                if (impliedSpeedMps > MAX_PLAUSIBLE_SPEED_MPS && consecutiveRejects < MAX_CONSECUTIVE_REJECTS) {
                    consecutiveRejects++
                    return null
                }
            }
        }
        consecutiveRejects = 0

        // 3. Jitter suppression — while stationary, a fix that's still within the
        // GPS noise radius of the last point isn't real movement; hold position
        // instead of letting the marker wobble.
        var effectiveLat = rawLat
        var effectiveLng = rawLng
        var held = false
        if (isStationary && prevLat != null && prevLng != null) {
            val jitterM = GeoMath.distanceMeters(prevLat, prevLng, rawLat, rawLng)
            // Android reports accuracy as a 68% confidence radius, so roughly a
            // third of genuine fixes land *outside* it. Taking that figure as the
            // noise radius therefore let about that share of pure noise through,
            // and each one that escaped moved the anchor — so a parked phone
            // random-walked instead of holding. Measured: 5.07 m of drift at 10 m
            // noise. Widening to ~95% confidence closes it.
            val jitterRadiusM = (accuracyM * ACCURACY_CONFIDENCE_SCALE).coerceAtLeast(MIN_JITTER_RADIUS_M)
            if (jitterM < jitterRadiusM) {
                effectiveLat = prevLat
                effectiveLng = prevLng
                held = true
            }
        }

        // 4. Position smoothing — 1D Kalman filter, still fed the (possibly held)
        // effective point so its variance keeps tightening on a stationary cluster.
        val (smoothLat, smoothLng) = kalmanUpdate(effectiveLat, effectiveLng, accuracyM.coerceAtLeast(1f), now)

        // 5. Speed refinement — EMA over raw GPS speed; zeroed while holding position
        // so a stationary collector doesn't show phantom drift speed.
        smoothedSpeedMps = if (lastAcceptedAtMs == 0L) {
            rawSpeedMps
        } else {
            SPEED_EMA_ALPHA * rawSpeedMps + (1 - SPEED_EMA_ALPHA) * smoothedSpeedMps
        }
        if (held) smoothedSpeedMps = 0f

        lastAcceptedLat = smoothLat
        lastAcceptedLng = smoothLng
        lastAcceptedAtMs = now
        lastAnyAcceptAtMs = now
        lastAcceptedAccuracyM = accuracyM

        return RefinedLocation(
            lat = smoothLat,
            lng = smoothLng,
            accuracyM = accuracyM,
            speedMps = smoothedSpeedMps,
            bearing = bearing,
            capturedAtMs = now,
        )
    }

    private fun kalmanUpdate(lat: Double, lng: Double, accuracyM: Float, nowMs: Long): Pair<Double, Double> {
        val prevLat = kalmanLat
        val prevLng = kalmanLng
        if (prevLat == null || prevLng == null || kalmanVarianceM2 < 0) {
            kalmanLat = lat
            kalmanLng = lng
            kalmanVarianceM2 = (accuracyM * accuracyM).toDouble()
            lastKalmanAtMs = nowMs
            return lat to lng
        }

        val elapsedMs = (nowMs - lastKalmanAtMs).coerceAtLeast(0L)
        // Process noise: uncertainty grows between fixes as if the collector could be
        // moving at a modest walking/riding pace — keeps the filter responsive instead
        // of over-trusting old fixes into an ever-shrinking "frozen" variance.
        kalmanVarianceM2 += elapsedMs * (PROCESS_NOISE_MPS * PROCESS_NOISE_MPS) / 1000.0

        val measurementVarianceM2 = (accuracyM * accuracyM).toDouble()
        val gain = kalmanVarianceM2 / (kalmanVarianceM2 + measurementVarianceM2)
        val newLat = prevLat + gain * (lat - prevLat)
        val newLng = prevLng + gain * (lng - prevLng)
        kalmanVarianceM2 *= (1 - gain)

        kalmanLat = newLat
        kalmanLng = newLng
        lastKalmanAtMs = nowMs
        return newLat to newLng
    }

    companion object {
        private const val MAX_ACCEPTABLE_ACCURACY_M = 50f
        private const val MAX_SILENCE_MS = 120_000L
        private const val MAX_PLAUSIBLE_SPEED_MPS = 41.7 // ~150 km/h — generous for toll-road travel, still catches teleports
        private const val MAX_CONSECUTIVE_REJECTS = 3
        private const val MIN_JITTER_RADIUS_M = 8f
        /** 68% -> ~95% confidence. See the jitter-radius comment above. */
        private const val ACCURACY_CONFIDENCE_SCALE = 1.6f
        private const val PROCESS_NOISE_MPS = 3.0
        private const val SPEED_EMA_ALPHA = 0.35f
    }
}

data class RefinedLocation(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val bearing: Float?,
    val capturedAtMs: Long,
)
