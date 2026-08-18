package com.collectionfield.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Measures what the filter actually does to a GPS stream, rather than asserting
 * the code exists. Each test feeds a synthetic stream and checks the property
 * that matters in the field.
 */
class LocationRefinerTest {

    private val baseLat = -6.2088
    private val baseLng = 106.8456

    /** Metres -> degrees at Jakarta's latitude, near enough for test fixtures. */
    private fun metresToLat(m: Double) = m / 111_320.0
    private fun metresToLng(m: Double) = m / (111_320.0 * 0.994)

    private fun feed(
        refiner: LocationRefiner,
        lat: Double,
        lng: Double,
        timeMs: Long,
        accuracyM: Float = 12f,
        speedMps: Float = 0f,
        isStationary: Boolean = true,
    ) = refiner.refine(
        rawLat = lat,
        rawLng = lng,
        accuracyM = accuracyM,
        rawSpeedMps = speedMps,
        bearing = null,
        timeMs = timeMs,
        isStationary = isStationary,
    )

    @Test
    fun `stationary jitter is suppressed instead of wobbling the marker`() {
        val refiner = LocationRefiner()
        val rng = Random(42)
        var t = 1_000_000L

        // Settle on a first fix.
        val first = feed(refiner, baseLat, baseLng, t)!!
        val outputs = mutableListOf(first.lat to first.lng)

        // 40 fixes of pure noise: the phone is parked, GPS drifts +-10 m.
        repeat(40) {
            t += 5_000
            val dN = (rng.nextDouble() - 0.5) * 20.0
            val dE = (rng.nextDouble() - 0.5) * 20.0
            val r = feed(refiner, baseLat + metresToLat(dN), baseLng + metresToLng(dE), t)
            assertNotNull("fix bersih tidak boleh dibuang", r)
            outputs += r!!.lat to r.lng
        }

        // How far did the reported position wander from where it started?
        val maxDriftM = outputs.maxOf { (la, ln) ->
            GeoMath.distanceMeters(first.lat, first.lng, la, ln)
        }

        // Raw input wanders up to ~14 m (10 m on each axis). With the hold active
        // the reported position should not move at all; see JitterMeasurementTest
        // for the with/without comparison this figure comes from.
        assertTrue("hanyut $maxDriftM m — anti-jitter tidak menahan posisi", maxDriftM < 0.5)
    }

    @Test
    fun `real movement is not mistaken for jitter`() {
        val refiner = LocationRefiner()
        var t = 1_000_000L
        feed(refiner, baseLat, baseLng, t, isStationary = false)

        // Walk 30 m north per fix for 10 fixes — 300 m of genuine travel.
        var lat = baseLat
        repeat(10) {
            t += 5_000
            lat += metresToLat(30.0)
            feed(refiner, lat, baseLng, t, speedMps = 6f, isStationary = false)
        }
        val last = feed(refiner, lat, baseLng, t + 5_000, speedMps = 6f, isStationary = false)!!

        val travelled = GeoMath.distanceMeters(baseLat, baseLng, last.lat, last.lng)
        assertTrue("hanya bergerak $travelled m dari 300 m — gerakan nyata ikut ditahan", travelled > 250)
    }

    @Test
    fun `jitter is still suppressed when the classifier says stationary but GPS reports drift speed`() {
        // A parked phone often reports a small non-zero speed from noise. The hold
        // must key off the stationary flag and the distance, not the speed field.
        val refiner = LocationRefiner()
        var t = 1_000_000L
        val first = feed(refiner, baseLat, baseLng, t)!!

        repeat(20) {
            t += 5_000
            val r = feed(
                refiner,
                baseLat + metresToLat(6.0),
                baseLng + metresToLng(-6.0),
                t,
                speedMps = 0.4f,
                isStationary = true,
            )!!
            val drift = GeoMath.distanceMeters(first.lat, first.lng, r.lat, r.lng)
            assertTrue("hanyut $drift m padahal diam", drift < 2.0)
            assertEquals("kecepatan harus dinolkan saat ditahan", 0f, r.speedMps, 0.001f)
        }
    }

    @Test
    fun `hold releases once the phone leaves the noise radius`() {
        val refiner = LocationRefiner()
        var t = 1_000_000L
        val first = feed(refiner, baseLat, baseLng, t, accuracyM = 12f)!!

        // Inside the noise radius -> held.
        t += 5_000
        val held = feed(refiner, baseLat + metresToLat(9.0), baseLng, t, accuracyM = 12f)!!
        assertTrue(GeoMath.distanceMeters(first.lat, first.lng, held.lat, held.lng) < 2.0)

        // Well outside it -> must move, even though the flag still says stationary.
        // Otherwise a collector who walks off would stay pinned to the old spot.
        t += 5_000
        val moved = feed(refiner, baseLat + metresToLat(60.0), baseLng, t, accuracyM = 12f)!!
        val delta = GeoMath.distanceMeters(first.lat, first.lng, moved.lat, moved.lng)
        assertTrue("hanya bergeser $delta m — hold tidak pernah lepas", delta > 15.0)
    }

    @Test
    fun `low accuracy fixes are dropped`() {
        val refiner = LocationRefiner()
        val t = 1_000_000L
        assertNotNull(feed(refiner, baseLat, baseLng, t, accuracyM = 12f))
        assertNull(
            "fix akurasi 80 m seharusnya dibuang",
            feed(refiner, baseLat, baseLng, t + 5_000, accuracyM = 80f),
        )
    }

    @Test
    fun `teleport is rejected then recovers so the map cannot stay stuck`() {
        val refiner = LocationRefiner()
        var t = 1_000_000L
        feed(refiner, baseLat, baseLng, t, isStationary = false)

        // 5 km away one second later — physically impossible.
        val far = baseLat + metresToLat(5_000.0)
        var rejects = 0
        var accepted: RefinedLocation? = null
        repeat(6) {
            t += 1_000
            val r = feed(refiner, far, baseLng, t, isStationary = false, speedMps = 5f)
            if (r == null) rejects++ else if (accepted == null) accepted = r
        }

        assertTrue("lompatan mustahil tidak ditolak sama sekali", rejects > 0)
        assertNotNull("tidak pernah pulih — posisi akan macet selamanya", accepted)
    }
}
