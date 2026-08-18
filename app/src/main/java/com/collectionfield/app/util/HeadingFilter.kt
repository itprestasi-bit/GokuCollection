package com.collectionfield.app.util

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a noisy stream of compass azimuths into something worth transmitting.
 *
 * Two jobs, both of which have to respect that this is an *angle*:
 *
 *  - **Smoothing.** A phone lying still still reports 2-5 degrees of magnetometer
 *    jitter. Averaging angles arithmetically is wrong — the mean of 350 and 10 is
 *    0, not 180 — so the smoothing runs on the unit circle instead.
 *  - **Gating.** Reporting every sensor tick would be ~50 updates a second. This
 *    releases a value only when the direction has genuinely turned, or when
 *    enough time has passed that the dashboard deserves a refresh anyway.
 *
 * Deliberately has no Android dependencies so the behaviour can be unit-tested.
 */
class HeadingFilter(
    /** Degrees of change that count as a real turn rather than sensor noise. */
    private val minChangeDeg: Float = 12f,
    /** Emit at least this often while the phone is being carried, even if steady. */
    private val maxQuietMs: Long = 10_000L,
    /** 0..1 — higher follows the sensor faster, lower is calmer. */
    private val smoothingAlpha: Float = 0.25f,
) {
    private var smoothedX = 0.0
    private var smoothedY = 0.0
    private var hasValue = false

    private var lastEmitted: Float? = null
    private var lastEmitAtMs = 0L

    /** Current smoothed heading in degrees (0..360), or null before the first sample. */
    val smoothed: Float?
        get() = if (!hasValue) null else normalize(Math.toDegrees(atan2(smoothedY, smoothedX)).toFloat())

    /**
     * Feeds one raw azimuth. Returns the heading to transmit, or null to stay quiet.
     */
    fun accept(azimuthDeg: Float, nowMs: Long): Float? {
        val rad = Math.toRadians(azimuthDeg.toDouble())
        val x = cos(rad)
        val y = sin(rad)

        if (!hasValue) {
            smoothedX = x
            smoothedY = y
            hasValue = true
        } else {
            // Exponential moving average over the unit-circle components, which is
            // what keeps the wrap at 0/360 from producing a wild swing.
            smoothedX += smoothingAlpha * (x - smoothedX)
            smoothedY += smoothingAlpha * (y - smoothedY)
        }

        val current = smoothed ?: return null
        val previous = lastEmitted

        val turned = previous == null || abs(shortestDelta(previous, current)) >= minChangeDeg
        val quietTooLong = nowMs - lastEmitAtMs >= maxQuietMs

        if (turned || quietTooLong) {
            lastEmitted = current
            lastEmitAtMs = nowMs
            return current
        }
        return null
    }

    fun reset() {
        hasValue = false
        lastEmitted = null
        lastEmitAtMs = 0L
    }

    companion object {
        /** Wraps any angle into 0..360. */
        fun normalize(deg: Float): Float {
            var d = deg % 360f
            if (d < 0f) d += 360f
            return d
        }

        /** Signed shortest rotation from [from] to [to], in -180..180. */
        fun shortestDelta(from: Float, to: Float): Float {
            var delta = (to - from) % 360f
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            return delta
        }
    }
}
