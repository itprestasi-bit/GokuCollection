package com.collectionfield.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The end-to-end question a supervisor actually asks: the phone is sitting on a
 * table, so why is the marker crawling around the map?
 *
 * Existing tests cover the refiner and the gate separately, and each passes on its
 * own. What reaches the dashboard is the two composed — a wobble only shows up
 * when a fix survives the refiner *and* clears the gate's movement threshold — so
 * this measures the pair, over a realistic parked half hour.
 */
class ParkedMarkerTest {

    private val baseLat = -6.2088
    private val baseLng = 106.8456
    private fun mToLat(m: Double) = m / 111_320.0
    private fun mToLng(m: Double) = m / (111_320.0 * 0.994)

    /**
     * [settleM] is where the marker comes to rest relative to the true spot — an
     * offset, not a fault; GPS has to pick somewhere. [wanderM] is the total
     * distance the marker walks *after* it has settled, and [jumpM] the largest
     * single hop. Those two are the complaint: a marker 8 m off but still is
     * fine, a marker that paces back and forth is not.
     */
    private data class Wander(val settleM: Double, val wanderM: Double, val jumpM: Double, val moves: Int)

    /**
     * @param useDetector true reproduces the shipping pipeline. False is the worst
     *   case where the stationary detector never concludes anything and the jitter
     *   hold therefore never engages — the state the phone is in for the first
     *   minute of every stop.
     */
    private fun park(noiseM: Double, accuracyM: Float, useDetector: Boolean, seed: Int): Wander {
        val refiner = LocationRefiner()
        val gate = TelemetryGate()
        val detector = StationaryDetector()
        val rng = Random(seed)
        var t = 1_000_000L

        var settleM = 0.0
        var wanderM = 0.0
        var jumpM = 0.0
        var moves = 0
        var lastShownLat = 0.0
        var lastShownLng = 0.0
        val settleAfterMs = 1_000_000L + 60_000L // ignore the first minute of lock-on

        // Half an hour parked, a fix every 5 seconds.
        repeat(360) {
            t += 5_000
            val dN = (rng.nextDouble() - 0.5) * 2 * noiseM
            val dE = (rng.nextDouble() - 0.5) * 2 * noiseM
            val lat = baseLat + mToLat(dN)
            val lng = baseLng + mToLng(dE)

            val parked = detector.accept(lat, lng, t)
            // GPS invents speed from its own noise while standing still.
            val phantomSpeed = (rng.nextFloat() * rng.nextFloat() * 1.5f)

            val r = refiner.refine(
                rawLat = lat,
                rawLng = lng,
                accuracyM = accuracyM,
                rawSpeedMps = phantomSpeed,
                bearing = null,
                timeMs = t,
                isStationary = if (useDetector) parked else false,
            ) ?: return@repeat

            val decision = gate.evaluate(r.lat, r.lng, t)
            if (!decision.sendLive) return@repeat

            // Only a transmitted position can move the marker.
            if (lastShownLat != 0.0) {
                val step = GeoMath.distanceMeters(lastShownLat, lastShownLng, r.lat, r.lng)
                if (t >= settleAfterMs) {
                    wanderM += step
                    jumpM = maxOf(jumpM, step)
                    if (step > 1.0) moves++
                }
            }
            lastShownLat = r.lat
            lastShownLng = r.lng
            if (t >= settleAfterMs) {
                settleM = GeoMath.distanceMeters(baseLat, baseLng, r.lat, r.lng)
            }
        }
        return Wander(settleM, wanderM, jumpM, moves)
    }

    @Test
    fun `report how far the marker wanders while the phone sits still`() {
        println("=".repeat(86))
        println("  kondisi GPS            |        pipeline penuh        |     tanpa deteksi diam")
        println("                         | jarak jalan  lompat  pindah  | jarak jalan  lompat  pindah")
        println("-".repeat(86))
        for ((label, pair) in listOf(
            "bagus (derau 5 m)" to (5.0 to 10f),
            "sedang (derau 10 m)" to (10.0 to 15f),
            "gedung tinggi (20 m)" to (20.0 to 25f),
            "multipath berat (30 m)" to (30.0 to 40f),
        )) {
            val (noise, acc) = pair
            val on = (0 until 8).map { park(noise, acc, useDetector = true, seed = it) }
            val off = (0 until 8).map { park(noise, acc, useDetector = false, seed = it) }
            println(
                "  %-22s | %7.1f m %6.1f m %5.1fx | %7.1f m %6.1f m %5.1fx".format(
                    label,
                    on.map { it.wanderM }.average(), on.map { it.jumpM }.average(), on.map { it.moves }.average(),
                    off.map { it.wanderM }.average(), off.map { it.jumpM }.average(), off.map { it.moves }.average(),
                )
            )
        }
        println("=".repeat(86))
        println("  29 menit setelah HP terkunci posisi, HP benar-benar diam di satu titik")
    }

    @Test
    fun `a parked phone does not walk the marker across the map`() {
        // Ordinary urban GPS, full pipeline. Settling a few metres from the true
        // spot is unavoidable; pacing around after that is the bug.
        val runs = (0 until 12).map { park(noiseM = 10.0, accuracyM = 15f, useDetector = true, seed = it) }
        val worstWander = runs.maxOf { it.wanderM }
        val worstJump = runs.maxOf { it.jumpM }
        assertTrue("marker berjalan $worstWander m padahal HP diam", worstWander < 1.0)
        assertTrue("marker melompat $worstJump m padahal HP diam", worstJump < 1.0)
    }
}
