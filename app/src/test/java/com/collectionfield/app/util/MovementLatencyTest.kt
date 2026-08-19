package com.collectionfield.app.util

import com.collectionfield.app.domain.TrackingMode
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * How long after a collector sets off does the dashboard show them moving.
 *
 * This is the number the field complains about, and nothing else in the suite
 * measured it: every other test either starts already moving or never moves. It
 * runs the whole chain — classifier, stationary detector, refiner, telemetry gate
 * — because the latency was spread across all four, not sitting in any one.
 */
class MovementLatencyTest {

    private val baseLat = -6.2088
    private val baseLng = 106.8456
    private fun mToLat(m: Double) = m / 111_320.0
    private fun mToLng(m: Double) = m / (111_320.0 * 0.994)

    /**
     * [statusS] is how long until the phone reports a non-zero speed — "this person
     * is moving", which Doppler can answer in a single fix. [markerS] is how long
     * until the marker has visibly left the spot, which no amount of code can make
     * faster than the collector outruns the GPS noise: two seconds of walking is
     * 2.8 m against a fix accurate to 12 m, and that information does not exist yet.
     */
    private data class Run(
        val statusS: Double,
        val markerS: Double,
        val parkedSendsPerMin: Double,
        val parkedWalkM: Double,
    )

    /**
     * Parks the phone for [parkMinutes], then walks away at [speedMps].
     * Returns how long the dashboard kept showing the old position.
     */
    private fun setOff(speedMps: Double, noiseM: Double, accuracyM: Float, intervalMs: Long, parkMinutes: Int, seed: Int): Run {
        val classifier = MovementClassifier()
        val detector = StationaryDetector()
        val refiner = LocationRefiner()
        val gate = TelemetryGate()
        val rng = Random(seed)

        var t = 1_000_000L
        var north = 0.0
        var parkedSends = 0
        var parkedWalk = 0.0
        var lastLat = 0.0
        var lastLng = 0.0
        var restLat = 0.0
        var restLng = 0.0
        var departAt = 0L
        var statusAt = 0L
        var markerAt = 0L

        val parkSteps = (parkMinutes * 60_000L / intervalMs).toInt()
        val walkSteps = (60_000L / intervalMs).toInt()

        repeat(parkSteps + walkSteps) { i ->
            val moving = i >= parkSteps
            if (moving) {
                if (departAt == 0L) { departAt = t; restLat = lastLat; restLng = lastLng }
                north += speedMps * (intervalMs / 1000.0)
            }
            t += intervalMs

            // A parked phone still reports a wandering speed; a moving one reports
            // roughly the truth.
            val reportedSpeed = if (moving) {
                (speedMps + (rng.nextDouble() - 0.5) * 0.6).coerceAtLeast(0.0)
            } else {
                rng.nextDouble() * rng.nextDouble() * 1.5
            }

            val lat = baseLat + mToLat(north + (rng.nextDouble() - 0.5) * 2 * noiseM)
            val lng = baseLng + mToLng((rng.nextDouble() - 0.5) * 2 * noiseM)

            val mode = classifier.accept(reportedSpeed.toFloat())
            val parked = detector.accept(lat, lng, t)
            val r = refiner.refine(
                rawLat = lat, rawLng = lng, accuracyM = accuracyM,
                rawSpeedMps = reportedSpeed.toFloat(), bearing = null, timeMs = t,
                isStationary = mode == TrackingMode.STOPPED && parked,
            ) ?: return@repeat

            if (!gate.evaluate(r.lat, r.lng, t, held = r.held, speedMps = r.speedMps).sendLive) return@repeat

            if (!moving) {
                parkedSends++
                if (lastLat != 0.0) parkedWalk += GeoMath.distanceMeters(lastLat, lastLng, r.lat, r.lng)
            } else if (restLat != 0.0) {
                if (statusAt == 0L && r.speedMps >= 1.0f) statusAt = t
                if (markerAt == 0L &&
                    GeoMath.distanceMeters(restLat, restLng, r.lat, r.lng) > accuracyM
                ) markerAt = t
            }
            lastLat = r.lat
            lastLng = r.lng
        }

        return Run(
            statusS = if (statusAt == 0L) Double.NaN else (statusAt - departAt) / 1000.0,
            markerS = if (markerAt == 0L) Double.NaN else (markerAt - departAt) / 1000.0,
            parkedSendsPerMin = parkedSends.toDouble() / parkMinutes,
            parkedWalkM = parkedWalk,
        )
    }

    @Test
    fun `report how long until movement shows on the dashboard`() {
        println("=".repeat(88))
        println("  skenario                | status bergerak | marker bergeser | diam: kirim/mnt | diam: geser")
        println("-".repeat(88))
        fun row(label: String, speed: Double, noise: Double, acc: Float) {
            val runs = (0 until 10).map { setOff(speed, noise, acc, 2_000L, 3, it) }
            fun avg(f: (Run) -> Double) = runs.map(f).filter { !it.isNaN() }.let {
                if (it.isEmpty()) Double.NaN else it.average()
            }
            println(
                "  %-23s | %12.1f s | %12.1f s | %13.1f | %9.1f m".format(
                    label, avg { it.statusS }, avg { it.markerS },
                    runs.map { it.parkedSendsPerMin }.average(),
                    runs.map { it.parkedWalkM }.average(),
                )
            )
        }
        row("jalan kaki 1,4 m/s", 1.4, 4.0, 12f)
        row("jalan cepat 2,2 m/s", 2.2, 4.0, 12f)
        row("motor pelan 5 m/s", 5.0, 4.0, 12f)
        row("motor 8 m/s", 8.0, 5.0, 15f)
        row("jalan kaki, GPS jelek", 1.4, 15.0, 25f)
        println("=".repeat(80))
    }

    @Test
    fun `setting off is visible within a few seconds`() {
        for ((speed, noise, acc) in listOf(
            Triple(1.4, 4.0, 12f),
            Triple(2.2, 4.0, 12f),
            Triple(5.0, 4.0, 12f),
            Triple(8.0, 5.0, 15f),
        )) {
            val runs = (0 until 10).map { setOff(speed, noise, acc, 2_000L, 3, it) }
            val status = runs.map { it.statusS }
            assertTrue("pada %.1f m/s status bergerak tidak pernah muncul".format(speed), status.none { it.isNaN() })
            val mean = status.average()
            assertTrue("pada %.1f m/s status bergerak baru muncul %.1f detik".format(speed, mean), mean <= 3.0)
        }
    }
}
