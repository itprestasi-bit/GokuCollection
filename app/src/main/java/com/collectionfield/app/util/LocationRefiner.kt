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
    private var consecutiveEscapes = 0
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
                    held = true,
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
        if (prevLat != null && prevLng != null) {
            val jitterM = GeoMath.distanceMeters(prevLat, prevLng, rawLat, rawLng)
            // Android reports accuracy as a 68% confidence radius, so roughly a
            // third of genuine fixes land *outside* it. Taking that figure as the
            // noise radius therefore let about that share of pure noise through,
            // and each one that escaped moved the anchor — so a parked phone
            // random-walked instead of holding. Measured: 5.07 m of drift at 10 m
            // noise. Widening to ~95% confidence closes it.
            //
            // Only a phone believed to be parked is ever held. An earlier version
            // held on every fix with a narrower radius, to protect the marker when
            // the stationary detector was wrong. It protected it too well: at a
            // 3 s interval a walker covers 4.2 m per fix while the radius is 8-20 m,
            // so the anchor did not move for four fixes and the reported speed was
            // pinned to zero the whole time — a collector walking down a street
            // showed on the dashboard as standing still. Noise on a moving phone is
            // a smaller problem than a moving phone that reads as parked.
            // How far the phone must move before the marker follows, graded by how
            // much the fix can be trusted. The old rule was a flat multiple of the
            // reported accuracy with an 8 m floor, which at the previous 50 m
            // ceiling meant a 19 m gate — wide enough that a walking collector
            // barely cleared it. With poor fixes now rejected outright the gate can
            // be metres rather than tens of metres.
            val movementGateM = when {
                accuracyM <= 7f -> 2.0f
                accuracyM <= PREFERRED_ACCURACY_M -> 2.5f
                accuracyM <= 12f -> 3.5f
                else -> 4.5f
            }
            val jitterRadiusM = if (isStationary) {
                (accuracyM * ACCURACY_CONFIDENCE_SCALE).coerceAtLeast(MIN_JITTER_RADIUS_M)
            } else {
                movementGateM
            }

            // GPS derives speed from Doppler shift, not from the positions it is
            // being asked to smooth, so it answers "is this thing moving" in a
            // single fix where displacement needs several. A phone that reports
            // real speed is never held, whatever the slower classifiers think:
            // waiting for them is what left a collector reading as parked while
            // they walked.
            // The hold now turns on the speed reading alone, not on what the slower
            // classifiers have concluded. Those classifiers are why a parked phone
            // still drifted: a phantom 0.6 m/s was enough to call it "slow" rather
            // than stopped, the hold switched off, and the noise went straight to
            // the map. Doppler is the honest signal in both directions — it releases
            // in one fix when someone sets off, and it stays low when they have not.
            // isStationary now only widens the radius once the slower signals agree.
            // A tighter fix than the last one is new information even standing
            // still: the position refines in place instead of waiting for the
            // collector to walk far enough to clear the noise gate. Without this a
            // marker that locked on at 14 m stayed at 14 m until someone moved.
            val sharper = accuracyM <= lastAcceptedAccuracyM - ACCURACY_IMPROVEMENT_M

            // ...and the converse: a fix that is *worse* than a good one it would
            // replace must show real movement first, or a settled marker gets
            // dragged around by the first degraded reading that arrives.
            val degrading = lastAcceptedAccuracyM in 0.01f..PREFERRED_ACCURACY_M &&
                accuracyM > PREFERRED_ACCURACY_M && jitterM < DEGRADED_MOVE_M

            val movingNow = rawSpeedMps >= MOVING_SPEED_OVERRIDE_MPS && !degrading
            if (movingNow || (sharper && !degrading)) {
                consecutiveEscapes = 0
            } else if (jitterM < jitterRadiusM) {
                effectiveLat = prevLat
                effectiveLng = prevLng
                held = true
                consecutiveEscapes = 0
            } else if (consecutiveEscapes + 1 < REQUIRED_ESCAPES) {
                // One fix beyond the noise radius is an outlier; two in a row is a
                // departure. Without this the anchor moved on every single stray
                // fix, and since the next hold then formed around the new spot, a
                // parked phone paced across the map — measured at 106 m of travel
                // in half an hour beside tall buildings, 218 m in heavy multipath,
                // while sitting perfectly still. Requiring persistence costs one
                // fix of latency when someone genuinely sets off.
                consecutiveEscapes++
                effectiveLat = prevLat
                effectiveLng = prevLng
                held = true
            }
            // Past the threshold the counter is deliberately left where it is.
            // Resetting it here would make the *next* fix a first escape again, so
            // a moving collector alternated held, released, held — travelling at
            // half speed and finishing 48 m behind. Only a fix that lands back
            // inside the radius means the phone has settled, and only that resets.
        }

        // 4. Speed refinement — EMA over raw GPS speed; zeroed while holding position
        // so a stationary collector doesn't show phantom drift speed. Computed
        // before the smoothing step because the filter below is tuned by it.
        // Asymmetric: quick to rise, slow to fall. A symmetric average starting from
        // a parked phone's near-zero reading needed three fixes to climb past
        // walking pace, so a collector setting off still read as stationary six
        // seconds later. Speeding up is news and is reported almost immediately;
        // slowing down is smoothed, because a single low sample is usually noise
        // rather than a stop.
        val alpha = if (rawSpeedMps > smoothedSpeedMps) SPEED_EMA_ATTACK else SPEED_EMA_ALPHA
        smoothedSpeedMps = if (lastAcceptedAtMs == 0L) {
            rawSpeedMps
        } else {
            alpha * rawSpeedMps + (1 - alpha) * smoothedSpeedMps
        }
        if (held) smoothedSpeedMps = 0f

        // 5. Position smoothing — 1D Kalman filter, still fed the (possibly held)
        // effective point so its variance keeps tightening on a stationary cluster.
        val (smoothLat, smoothLng) = kalmanUpdate(
            effectiveLat,
            effectiveLng,
            accuracyM.coerceAtLeast(1f),
            now,
            processNoiseMps = if (held) MIN_PROCESS_NOISE_MPS else {
                // Process noise answers "how far could they have gone since the last
                // fix without us knowing". Pinning it at a walking pace while the
                // collector rides at 15 m/s tells the filter each new fix must be
                // mostly error, so it creeps toward it — measured, that parked the
                // marker 102 m behind the rider. Scaling with observed speed is the
                // honest answer, and the max() with the raw reading keeps it from
                // lagging through an acceleration.
                maxOf(
                    MIN_PROCESS_NOISE_MPS,
                    maxOf(smoothedSpeedMps, rawSpeedMps).toDouble() * PROCESS_NOISE_SPEED_SCALE,
                )
            },
        )

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
            held = held,
        )
    }

    private fun kalmanUpdate(
        lat: Double,
        lng: Double,
        accuracyM: Float,
        nowMs: Long,
        processNoiseMps: Double,
    ): Pair<Double, Double> {
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
        // Uncertainty grows between fixes at the rate the collector is actually
        // travelling — see the call site. Squared because variance is in m².
        kalmanVarianceM2 += elapsedMs * (processNoiseMps * processNoiseMps) / 1000.0

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
        /**
         * Hard ceiling. A fix reporting worse than this is not used as a position.
         *
         * Reported accuracy is not something the app chooses — the GPS states it.
         * The only lever that raises it is refusing the poor ones, which is why
         * this moved from 50 m to 15 m: at 50 m the pipeline was free to publish a
         * position it had been told was half a football field wide. The cost is
         * availability, and it is paid for by the hold below rather than by the
         * marker vanishing.
         */
        private const val MAX_ACCEPTABLE_ACCURACY_M = 15f
        /** Below this a fix is trusted enough to move the marker on small displacement. */
        private const val PREFERRED_ACCURACY_M = 10f
        /** A fix this much tighter than the last is worth taking even standing still. */
        private const val ACCURACY_IMPROVEMENT_M = 2f
        /**
         * How long the filter will hold out for a fix inside the ceiling before
         * taking whatever it can get.
         *
         * The ceiling is a preference, not a refusal. Measured against an urban
         * alley, a hard 15 m reject published 4% of fixes and left 122-second gaps
         * — the marker effectively disappeared, which is worse than a marker that
         * is honestly labelled as rough. Twenty seconds keeps the position alive
         * while still preferring a good fix whenever one exists; the accuracy
         * travels with the position, so the dashboard can say which it is.
         */
        private const val MAX_SILENCE_MS = 20_000L
        private const val MAX_PLAUSIBLE_SPEED_MPS = 41.7 // ~150 km/h — generous for toll-road travel, still catches teleports
        private const val MAX_CONSECUTIVE_REJECTS = 3
        private const val MIN_JITTER_RADIUS_M = 8f
        /** 68% -> ~95% confidence. See the jitter-radius comment above. */
        private const val ACCURACY_CONFIDENCE_SCALE = 1.6f
        /** Consecutive fixes outside the radius before the anchor is allowed to move. */
        private const val REQUIRED_ESCAPES = 2
        /**
         * Doppler speed above which the phone is treated as moving no matter what.
         * A parked phone's speed field wanders up to about 1.5 m/s on noise, so this
         * sits above a slow walk and below that ceiling — deliberately biased toward
         * releasing the marker rather than holding it.
         */
        private const val MOVING_SPEED_OVERRIDE_MPS = 1.0f
        /** Displacement a degraded fix must show before it may replace a good one. */
        private const val DEGRADED_MOVE_M = 8.0
        /**
         * Floor for a standing or slow phone. Measured: dropping this to 2.0 cost
         * a walker 3 m of extra lag for no visible gain in steadiness, since the
         * jitter hold — not the filter — is what keeps a parked marker still.
         */
        private const val MIN_PROCESS_NOISE_MPS = 3.0
        /** Headroom above the measured speed for turns and acceleration between fixes. */
        private const val PROCESS_NOISE_SPEED_SCALE = 1.5
        private const val SPEED_EMA_ALPHA = 0.35f
        /** Weight given to a *rising* reading. See the note at the call site. */
        private const val SPEED_EMA_ATTACK = 0.75f
    }
}

data class RefinedLocation(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val bearing: Float?,
    val capturedAtMs: Long,
    /**
     * True when this fix was inside the noise radius and the previous position was
     * repeated instead. The coordinates are then identical to the last ones, so
     * there is nothing new to transmit — [TelemetryGate] uses this rather than
     * re-deriving "did it move" from the coordinates it was handed.
     */
    val held: Boolean = false,
)
