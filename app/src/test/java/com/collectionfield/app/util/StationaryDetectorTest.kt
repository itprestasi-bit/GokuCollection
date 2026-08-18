package com.collectionfield.app.util

import com.collectionfield.app.domain.TrackingMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StationaryDetectorTest {

    private val baseLat = -6.2088
    private val baseLng = 106.8456
    private fun mToLat(m: Double) = m / 111_320.0

    @Test
    fun `a parked phone is recognised despite heavy position noise`() {
        val detector = StationaryDetector()
        val rng = Random(3)
        var t = 0L
        var recognised = false
        // 20 m of scatter — the multipath case where the speed field reads several
        // m/s and the speed-based classifier fails.
        repeat(30) {
            t += 5_000
            val dN = (rng.nextDouble() - 0.5) * 40.0
            val dE = (rng.nextDouble() - 0.5) * 40.0
            if (detector.accept(baseLat + mToLat(dN), baseLng + mToLat(dE), t)) recognised = true
        }
        assertTrue("derau posisi masih membuat HP terbaca bergerak", recognised)
    }

    @Test
    fun `driving away is never called stationary`() {
        val detector = StationaryDetector()
        var t = 0L
        var lat = baseLat
        repeat(40) {
            t += 5_000
            lat += mToLat(60.0) // ~43 km/h
            assertFalse(
                "perjalanan nyata dianggap diam",
                detector.accept(lat, baseLng, t),
            )
        }
    }

    @Test
    fun `walking out of the radius resets the hold`() {
        val detector = StationaryDetector()
        var t = 0L
        repeat(10) {
            t += 5_000
            detector.accept(baseLat, baseLng, t)
        }
        assertTrue("belum terbaca diam padahal benar-benar diam", detector.accept(baseLat, baseLng, t + 5_000))

        // Walk steadily away: the newer half of the window pulls clear of the
        // older half, so the verdict must flip back to moving.
        var lat = baseLat
        var moving = false
        repeat(8) {
            t += 5_000
            lat += mToLat(40.0)
            if (!detector.accept(lat, baseLng, t)) moving = true
        }
        assertTrue("berjalan menjauh tapi masih dianggap diam", moving)
    }

    /** The before/after for the speed-only approach this replaces. */
    @Test
    fun `compare speed-only against displacement detection`() {
        fun speedOnly(peak: Float, seed: Int): Double {
            val c = MovementClassifier()
            val rng = Random(seed)
            var stopped = 0
            repeat(400) {
                val sp = rng.nextFloat() * rng.nextFloat() * peak
                if (c.accept(sp) == TrackingMode.STOPPED) stopped++
            }
            return stopped / 400.0
        }

        fun displacement(scatterM: Double, seed: Int): Double {
            val d = StationaryDetector()
            val rng = Random(seed)
            var t = 0L
            var stationary = 0
            repeat(400) {
                t += 5_000
                val dN = (rng.nextDouble() - 0.5) * 2 * scatterM
                val dE = (rng.nextDouble() - 0.5) * 2 * scatterM
                if (d.accept(baseLat + mToLat(dN), baseLng + mToLat(dE), t)) stationary++
            }
            return stationary / 400.0
        }

        println("=".repeat(62))
        println("  kondisi HP parkir        | speed-only | displacement")
        println("-".repeat(62))
        val cases = listOf(
            "GPS bagus (derau kecil)" to (0.8f to 5.0),
            "GPS sedang" to (1.5f to 10.0),
            "gedung tinggi" to (3.0f to 18.0),
            "gang sempit / multipath" to (5.0f to 25.0),
        )
        for ((label, pair) in cases) {
            val (peak, scatter) = pair
            val a = (0 until 10).map { speedOnly(peak, it) }.average()
            val b = (0 until 10).map { displacement(scatter, it) }.average()
            println("  %-24s | %8.1f%% | %10.1f%%".format(label, a * 100, b * 100))
        }
        println("=".repeat(62))
    }
}
