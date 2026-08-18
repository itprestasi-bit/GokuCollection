package com.collectionfield.app.util

import org.junit.Test
import kotlin.random.Random

/**
 * Not a pass/fail assertion — a measurement, printed so the numbers can be read
 * and argued with. Runs the same synthetic noise through the filter twice: once
 * with the stationary flag set (suppression on) and once without (off), and
 * reports how far the reported position wandered in each case.
 */
class JitterMeasurementTest {

    private val baseLat = -6.2088
    private val baseLng = 106.8456
    private fun mToLat(m: Double) = m / 111_320.0
    private fun mToLng(m: Double) = m / (111_320.0 * 0.994)

    private fun run(noiseM: Double, accuracyM: Float, stationary: Boolean, seed: Int): Pair<Double, Double> {
        val refiner = LocationRefiner()
        val rng = Random(seed)
        var t = 1_000_000L
        val first = refiner.refine(baseLat, baseLng, accuracyM, 0f, null, t, stationary)!!
        var maxDrift = 0.0
        var sumStep = 0.0
        var prevLat = first.lat
        var prevLng = first.lng
        val n = 60
        repeat(n) {
            t += 5_000
            val dN = (rng.nextDouble() - 0.5) * 2 * noiseM
            val dE = (rng.nextDouble() - 0.5) * 2 * noiseM
            val r = refiner.refine(
                baseLat + mToLat(dN), baseLng + mToLng(dE),
                accuracyM, 0f, null, t, stationary,
            ) ?: return@repeat
            maxDrift = maxOf(maxDrift, GeoMath.distanceMeters(first.lat, first.lng, r.lat, r.lng))
            sumStep += GeoMath.distanceMeters(prevLat, prevLng, r.lat, r.lng)
            prevLat = r.lat
            prevLng = r.lng
        }
        return maxDrift to (sumStep / n)
    }

    @Test
    fun `measure jitter suppression effect`() {
        println("=".repeat(66))
        println("noise  akurasi | SUPPRESSION OFF       | SUPPRESSION ON")
        println("               | maxDrift  avgStep     | maxDrift  avgStep")
        println("-".repeat(66))
        for ((noise, acc) in listOf(5.0 to 12f, 10.0 to 12f, 10.0 to 25f, 20.0 to 30f)) {
            var offD = 0.0
            var offS = 0.0
            var onD = 0.0
            var onS = 0.0
            val seeds = 20
            repeat(seeds) { s ->
                val off = run(noise, acc, stationary = false, seed = s)
                val on = run(noise, acc, stationary = true, seed = s)
                offD += off.first; offS += off.second
                onD += on.first; onS += on.second
            }
            println(
                "%5.0fm %5.0fm | %8.2fm %8.2fm | %8.2fm %8.2fm".format(
                    noise, acc, offD / seeds, offS / seeds, onD / seeds, onS / seeds,
                )
            )
        }
        println("=".repeat(66))
    }
}
