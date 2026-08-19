package com.collectionfield.app.util

import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Measures what the marker on the dashboard actually does against what the
 * collector actually did. Two properties matter and they pull against each other:
 *
 *  - **lag** — how far behind the true position the filtered output sits. A filter
 *    that smooths hard looks calm and points at where the collector *was*.
 *  - **jaggedness** — how much the reported track zig-zags relative to the real
 *    path. A filter that trusts every fix tracks perfectly and looks like a
 *    seismograph.
 *
 * These print numbers rather than asserting thresholds, so the trade being made
 * is visible instead of hidden behind a passing test.
 */
class TrackingSmoothnessTest {

    private val baseLat = -6.2088
    private val baseLng = 106.8456
    private fun mToLat(m: Double) = m / 111_320.0
    private fun mToLng(m: Double) = m / (111_320.0 * 0.994)

    private data class Result(val meanLagM: Double, val maxLagM: Double, val jaggednessM: Double)

    /**
     * Drives a synthetic track through the refiner and compares the output with
     * ground truth. [headingAt] returns the direction of travel in radians.
     */
    private fun run(
        speedMps: Double,
        intervalMs: Long,
        accuracyM: Float,
        steps: Int,
        seed: Int,
        headingAt: (Int) -> Double,
    ): Result {
        val refiner = LocationRefiner()
        val rng = Random(seed)
        var t = 1_000_000L
        var trueN = 0.0
        var trueE = 0.0

        val lags = mutableListOf<Double>()
        val outputs = mutableListOf<Pair<Double, Double>>()

        repeat(steps) { i ->
            val h = headingAt(i)
            val step = speedMps * (intervalMs / 1000.0)
            trueN += step * cos(h)
            trueE += step * sin(h)

            // Gaussian-ish measurement noise scaled to the reported accuracy.
            val noiseN = (rng.nextDouble() + rng.nextDouble() + rng.nextDouble() - 1.5) * accuracyM
            val noiseE = (rng.nextDouble() + rng.nextDouble() + rng.nextDouble() - 1.5) * accuracyM

            t += intervalMs
            val r = refiner.refine(
                rawLat = baseLat + mToLat(trueN + noiseN),
                rawLng = baseLng + mToLng(trueE + noiseE),
                accuracyM = accuracyM,
                rawSpeedMps = speedMps.toFloat(),
                bearing = null,
                timeMs = t,
                isStationary = false,
            ) ?: return@repeat

            // Skip the settling period; the first fixes carry no filter history.
            if (i > 5) {
                lags += GeoMath.distanceMeters(
                    baseLat + mToLat(trueN), baseLng + mToLng(trueE), r.lat, r.lng,
                )
            }
            outputs += r.lat to r.lng
        }

        // Jaggedness: how much longer the reported track is than the true distance
        // covered. A perfectly smooth track on a straight road scores ~0.
        var reported = 0.0
        for (i in 1 until outputs.size) {
            reported += GeoMath.distanceMeters(
                outputs[i - 1].first, outputs[i - 1].second, outputs[i].first, outputs[i].second,
            )
        }
        val trueDistance = speedMps * (intervalMs / 1000.0) * outputs.size
        return Result(
            meanLagM = lags.average(),
            maxLagM = lags.maxOrNull() ?: 0.0,
            jaggednessM = abs(reported - trueDistance) / outputs.size,
        )
    }

    @Test
    fun `report tracking lag and jaggedness across realistic scenarios`() {
        println("=".repeat(74))
        println("  skenario                     | lag rata2 | lag maks | kelebihan jarak/titik")
        println("-".repeat(74))

        fun line(label: String, r: Result) =
            println("  %-28s | %7.1f m | %6.1f m | %14.1f m".format(label, r.meanLagM, r.maxLagM, r.jaggednessM))

        line("jalan kaki 1,4 m/s", run(1.4, 5_000, 8f, 120, 1) { 0.0 })
        line("motor kota 8 m/s", run(8.0, 5_000, 12f, 120, 2) { 0.0 })
        line("mobil 15 m/s", run(15.0, 5_000, 12f, 120, 3) { 0.0 })
        line("motor belok terus", run(8.0, 5_000, 12f, 120, 4) { i -> i * 0.15 })
        line("mobil 15 m/s, GPS jelek", run(15.0, 5_000, 30f, 120, 5) { 0.0 })
        println("=".repeat(74))
    }
}
