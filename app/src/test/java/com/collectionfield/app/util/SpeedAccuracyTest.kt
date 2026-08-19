package com.collectionfield.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * How close the speed shown to the collector is to the speed they are actually
 * travelling.
 *
 * Accuracy here is 100% minus the mean absolute percentage error against ground
 * truth. GPS reports speed from Doppler shift rather than from position deltas,
 * which is good to roughly a few tenths of a m/s in the open and degrades in
 * cities, so the raw feed is modelled with noise of that size rather than as a
 * clean number — a test fed the true speed would measure nothing.
 */
class SpeedAccuracyTest {

    private val baseLat = -6.2088
    private val baseLng = 106.8456
    private fun mToLat(m: Double) = m / 111_320.0

    /** Mean absolute percentage error of the reported speed, as an accuracy score. */
    private fun accuracy(
        trueSpeedMps: Double,
        gpsNoiseMps: Double,
        intervalMs: Long,
        accuracyM: Float,
        seed: Int,
        steps: Int = 200,
    ): Double {
        val refiner = LocationRefiner()
        val rng = Random(seed)
        var t = 1_000_000L
        var north = 0.0
        val errors = mutableListOf<Double>()

        repeat(steps) { i ->
            north += trueSpeedMps * (intervalMs / 1000.0)
            t += intervalMs
            // Doppler noise is roughly symmetric and cannot go below zero.
            val raw = (trueSpeedMps + (rng.nextDouble() + rng.nextDouble() - 1.0) * gpsNoiseMps)
                .coerceAtLeast(0.0)

            val r = refiner.refine(
                rawLat = baseLat + mToLat(north),
                rawLng = baseLng,
                accuracyM = accuracyM,
                rawSpeedMps = raw.toFloat(),
                bearing = null,
                timeMs = t,
                isStationary = false,
            ) ?: return@repeat

            if (i > 10) errors += abs(r.speedMps - trueSpeedMps) / trueSpeedMps
        }
        return (1.0 - errors.average()) * 100.0
    }

    @Test
    fun `report speed accuracy across realistic conditions`() {
        println("=".repeat(70))
        println("  kondisi                          | interval | akurasi kecepatan")
        println("-".repeat(70))
        fun row(label: String, speed: Double, noise: Double, interval: Long, acc: Float) {
            val a = (0 until 10).map { accuracy(speed, noise, interval, acc, it) }.average()
            println("  %-32s | %5d ms | %14.1f%%".format(label, interval, a))
        }
        for (interval in listOf(5_000L, 3_000L)) {
            row("jalan kaki 1,4 m/s", 1.4, 0.4, interval, 10f)
            row("motor kota 8 m/s", 8.0, 0.6, interval, 12f)
            row("mobil 15 m/s", 15.0, 0.8, interval, 12f)
            row("motor, GPS kota padat", 8.0, 1.5, interval, 25f)
            println("-".repeat(70))
        }
        println("=".repeat(70))
    }

    @Test
    fun `speed is at least 80 percent accurate in ordinary conditions`() {
        val cases = listOf(
            Triple(1.4, 0.4, 10f),
            Triple(8.0, 0.6, 12f),
            Triple(15.0, 0.8, 12f),
            Triple(8.0, 1.5, 25f),
        )
        for ((speed, noise, acc) in cases) {
            val a = (0 until 10).map { accuracy(speed, noise, 3_000L, acc, it) }.average()
            assertTrue("akurasi kecepatan pada %.1f m/s hanya %.1f%%".format(speed, a), a >= 80.0)
        }
    }
}
