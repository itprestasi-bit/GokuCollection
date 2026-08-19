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
    fun `a lone stray fix does not move the marker, but a real departure does`() {
        val refiner = LocationRefiner()
        var t = 1_000_000L
        val first = feed(refiner, baseLat, baseLng, t, accuracyM = 12f)!!

        // Inside the noise radius -> held.
        t += 5_000
        val held = feed(refiner, baseLat + metresToLat(9.0), baseLng, t, accuracyM = 12f)!!
        assertTrue(GeoMath.distanceMeters(first.lat, first.lng, held.lat, held.lng) < 2.0)

        // A single fix well outside the radius is an outlier, not a journey. Acting
        // on each of these is what walked a parked marker 200 m across the map.
        t += 5_000
        val stray = feed(refiner, baseLat + metresToLat(60.0), baseLng, t, accuracyM = 12f)!!
        assertTrue(
            "satu fix nyasar langsung menggeser marker",
            GeoMath.distanceMeters(first.lat, first.lng, stray.lat, stray.lng) < 2.0,
        )

        // Confirmed by a second fix in the same place -> the collector really has
        // left, and the marker must follow. Otherwise they stay pinned for good.
        t += 5_000
        val moved = feed(refiner, baseLat + metresToLat(60.0), baseLng, t, accuracyM = 12f)!!
        val delta = GeoMath.distanceMeters(first.lat, first.lng, moved.lat, moved.lng)
        assertTrue("hanya bergeser $delta m — hold tidak pernah lepas", delta > 15.0)
    }

    @Test
    fun `a coarse fix that contradicts nothing holds the position instead of moving it`() {
        // The +-100 m bug. A cell-tower fix 40 m away with 100 m of error is exactly
        // what a phone that has not moved reports; taking it would swap a 12 m
        // position for a 100 m one and shift the marker for no reason.
        val refiner = LocationRefiner()
        var t = 1_000_000L
        val good = feed(refiner, baseLat, baseLng, t, accuracyM = 12f)!!

        repeat(10) {
            t += 30_000
            val r = feed(
                refiner,
                baseLat + metresToLat(40.0),
                baseLng,
                t,
                accuracyM = 100f,
                isStationary = false,
            )
            assertNotNull("harus tetap mengirim detak, bukan hilang dari peta", r)
            val moved = GeoMath.distanceMeters(good.lat, good.lng, r!!.lat, r.lng)
            assertEquals("posisi bergeser $moved m karena fix kasar", 0.0, moved, 0.001)
            assertEquals("akurasi dilaporkan dari fix yang jadi sumber koordinat", 12f, r.accuracyM, 0.001f)
        }
    }

    @Test
    fun `a coarse fix far from the last position is dropped, then taken once nothing better arrives`() {
        val refiner = LocationRefiner()
        var t = 1_000_000L
        feed(refiner, baseLat, baseLng, t, accuracyM = 12f)

        // 800 m away, well beyond the fix's own 100 m error — a real contradiction,
        // but not yet worth acting on while good fixes might still turn up.
        val far = baseLat + metresToLat(800.0)
        t += 30_000
        assertNull(
            "fix kasar yang bertentangan tidak boleh langsung dipakai",
            feed(refiner, far, baseLng, t, accuracyM = 100f, isStationary = false),
        )

        // Past the silence window with nothing better: a rough position now beats a
        // confidently stale one, otherwise the marker would sit there forever.
        t += 130_000
        assertNotNull(
            "setelah lama tanpa fix bagus, posisi kasar harus dipakai",
            feed(refiner, far, baseLng, t, accuracyM = 100f, isStationary = false),
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
