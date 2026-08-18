package com.collectionfield.app.util

/**
 * Decides whether the phone is actually standing still, from displacement rather
 * than from the reported speed.
 *
 * The speed field cannot be trusted for this. It is derived from successive
 * positions, so in a street lined with buildings the same multipath error that
 * scatters the position also manufactures speed: a parked phone whose fix jumps
 * 20 m between samples reads as 4 m/s. Measured against that noise, speed alone
 * recognised a parked phone barely a quarter of the time in the worst case — and
 * the jitter suppression it gates never engaged, which is exactly when the
 * marker was seen wandering.
 *
 * The test is a comparison of **averages**, not of individual fixes. An earlier
 * version asked "has every fix stayed within R metres of an anchor?", which
 * measured worse than the speed field it replaced: heavy scatter throws the odd
 * fix well past any sane radius, each excursion reset the anchor, and the timer
 * never completed. Averaging fixes that behaviour at the root — zero-mean noise
 * cancels out over a window, real travel does not. So the window is split in
 * half and the two halves' centroids compared: if the phone has genuinely gone
 * somewhere, the newer centroid has moved; if it is just noise, both land on the
 * same spot however wildly the individual fixes scattered.
 */
class StationaryDetector(
    /** How far the centroid may shift and still count as standing still. */
    private val maxCentroidShiftM: Double = 12.0,
    /** Fixes older than this leave the window. */
    private val windowMs: Long = 60_000L,
    /** The window must span at least this long before a verdict is given. */
    private val minSpanMs: Long = 20_000L,
) {
    private data class Sample(val lat: Double, val lng: Double, val timeMs: Long)

    private val window = ArrayDeque<Sample>()

    /** Feeds one raw fix. True once the recent average has held one spot. */
    fun accept(lat: Double, lng: Double, timeMs: Long): Boolean {
        window.addLast(Sample(lat, lng, timeMs))
        while (window.isNotEmpty() && timeMs - window.first().timeMs > windowMs) {
            window.removeFirst()
        }

        if (window.size < 6) return false
        val span = timeMs - window.first().timeMs
        if (span < minSpanMs) return false

        val half = window.size / 2
        val older = window.take(half)
        val newer = window.drop(half)
        if (older.isEmpty() || newer.isEmpty()) return false

        val (oLat, oLng) = centroid(older)
        val (nLat, nLng) = centroid(newer)
        return GeoMath.distanceMeters(oLat, oLng, nLat, nLng) <= maxCentroidShiftM
    }

    private fun centroid(samples: List<Sample>): Pair<Double, Double> =
        samples.sumOf { it.lat } / samples.size to samples.sumOf { it.lng } / samples.size

    fun reset() = window.clear()
}
