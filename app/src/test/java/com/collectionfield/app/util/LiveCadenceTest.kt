package com.collectionfield.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * How often the dashboard marker is actually refreshed, and how often it is
 * refreshed for no reason.
 *
 * The two requirements pull in opposite directions — a new position every three
 * seconds while travelling, and near silence while parked — so both are measured
 * in the same run of the same pipeline rather than separately.
 */
class LiveCadenceTest {

    private val baseLat = -6.2088
    private val baseLng = 106.8456
    private fun mToLat(m: Double) = m / 111_320.0
    private fun mToLng(m: Double) = m / (111_320.0 * 0.994)

    private data class Feed(val sendsPerMinute: Double, val medianGapS: Double, val markerWalkM: Double)

    private fun run(
        speedMps: Double,
        noiseM: Double,
        accuracyM: Float,
        intervalMs: Long,
        minutes: Int,
        seed: Int,
    ): Feed {
        val refiner = LocationRefiner()
        val gate = TelemetryGate()
        val detector = StationaryDetector()
        val rng = Random(seed)
        var t = 1_000_000L
        var north = 0.0

        val sendTimes = mutableListOf<Long>()
        var walk = 0.0
        var lastLat = 0.0
        var lastLng = 0.0

        val steps = (minutes * 60_000L / intervalMs).toInt()
        repeat(steps) {
            north += speedMps * (intervalMs / 1000.0)
            t += intervalMs
            val lat = baseLat + mToLat(north + (rng.nextDouble() - 0.5) * 2 * noiseM)
            val lng = baseLng + mToLng((rng.nextDouble() - 0.5) * 2 * noiseM)

            val parked = detector.accept(lat, lng, t)
            val r = refiner.refine(
                rawLat = lat,
                rawLng = lng,
                accuracyM = accuracyM,
                rawSpeedMps = speedMps.toFloat(),
                bearing = null,
                timeMs = t,
                isStationary = parked,
            ) ?: return@repeat

            if (gate.evaluate(r.lat, r.lng, t, held = r.held).sendLive) {
                sendTimes += t
                if (lastLat != 0.0) walk += GeoMath.distanceMeters(lastLat, lastLng, r.lat, r.lng)
                lastLat = r.lat
                lastLng = r.lng
            }
        }

        val gaps = sendTimes.zipWithNext { a, b -> (b - a) / 1000.0 }.sorted()
        return Feed(
            sendsPerMinute = sendTimes.size.toDouble() / minutes,
            medianGapS = if (gaps.isEmpty()) 0.0 else gaps[gaps.size / 2],
            markerWalkM = walk,
        )
    }

    @Test
    fun `report live refresh rate while moving and while parked`() {
        println("=".repeat(76))
        println("  skenario                  | kiriman/menit | jeda tengah | marker berjalan")
        println("-".repeat(76))
        fun row(label: String, speed: Double, noise: Double, acc: Float) {
            val f = run(speed, noise, acc, 3_000L, 20, 7)
            println("  %-25s | %11.1f/m | %8.1f s | %11.1f m".format(label, f.sendsPerMinute, f.medianGapS, f.markerWalkM))
        }
        row("jalan kaki 1,4 m/s", 1.4, 3.0, 10f)
        row("motor kota 8 m/s", 8.0, 4.0, 12f)
        row("mobil 15 m/s", 15.0, 4.0, 12f)
        row("DIAM, GPS bagus", 0.0, 5.0, 10f)
        row("DIAM, gedung tinggi", 0.0, 20.0, 25f)
        println("=".repeat(76))
    }

    @Test
    fun `a moving collector refreshes every three seconds`() {
        for ((speed, noise, acc) in listOf(
            Triple(1.4, 3.0, 10f),
            Triple(8.0, 4.0, 12f),
            Triple(15.0, 4.0, 12f),
        )) {
            val f = run(speed, noise, acc, 3_000L, 20, 3)
            assertTrue(
                "pada %.1f m/s jeda tengahnya %.1f s, bukan 3 s".format(speed, f.medianGapS),
                f.medianGapS <= 3.5,
            )
        }
    }

    @Test
    fun `a parked phone stays quiet and the marker stays put`() {
        // Heartbeat backs off to one every five minutes, so a 20-minute stop should
        // cost a handful of writes and move the marker essentially not at all.
        val f = run(0.0, 20.0, 25f, 3_000L, 20, 5)
        assertTrue("HP diam mengirim %.1f kali per menit".format(f.sendsPerMinute), f.sendsPerMinute < 1.0)
        assertTrue("marker berjalan %.1f m padahal diam".format(f.markerWalkM), f.markerWalkM < 15.0)
    }
}
