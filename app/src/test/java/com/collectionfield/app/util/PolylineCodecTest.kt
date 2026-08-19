package com.collectionfield.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PolylineCodecTest {

    @Test
    fun `matches Google's published example`() {
        // The reference string from Google's own encoded-polyline documentation.
        // Interoperability matters: the dashboard decodes this with a JS
        // implementation, so "round-trips with itself" would not be enough.
        val decoded = PolylineCodec.decodePath("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, decoded.size)
        assertEquals(38.5, decoded[0].first, 1e-5)
        assertEquals(-120.2, decoded[0].second, 1e-5)
        assertEquals(40.7, decoded[1].first, 1e-5)
        assertEquals(-120.95, decoded[1].second, 1e-5)
        assertEquals(43.252, decoded[2].first, 1e-5)
        assertEquals(-126.453, decoded[2].second, 1e-5)
    }

    @Test
    fun `a realistic shift survives the round trip within a metre`() {
        val rng = Random(9)
        var lat = -6.2088
        var lng = 106.8456
        val points = (0 until 14_400).map {
            lat += (rng.nextDouble() - 0.5) * 0.0004
            lng += (rng.nextDouble() - 0.5) * 0.0004
            lat to lng
        }

        val encoded = PolylineCodec.encodePath(points)
        val back = PolylineCodec.decodePath(encoded)

        assertEquals(points.size, back.size)
        val worst = points.indices.maxOf {
            GeoMath.distanceMeters(points[it].first, points[it].second, back[it].first, back[it].second)
        }
        assertTrue("meleset $worst m setelah encode/decode", worst < 1.5)

        // The size claim this whole design rests on.
        val perPoint = encoded.length.toDouble() / points.size
        println("14.400 titik -> ${encoded.length / 1024} KB (${"%.1f".format(perPoint)} byte/titik)")
        assertTrue("terlalu besar: ${encoded.length} byte", encoded.length < 400_000)
    }

    @Test
    fun `timestamps and speeds ride along without loss`() {
        val times = (0 until 5_000).map { it * 2L }
        val speeds = (0 until 5_000).map { (it % 40) / 2f }

        assertEquals(times, PolylineCodec.decodeValues(PolylineCodec.encodeValues(times)))

        val speedsBack = PolylineCodec.deciToSpeeds(
            PolylineCodec.decodeValues(PolylineCodec.encodeValues(PolylineCodec.speedsToDeci(speeds)))
        )
        val worst = speeds.indices.maxOf { abs(speeds[it] - speedsBack[it]) }
        assertTrue("kecepatan meleset $worst m/s", worst <= 0.05f)
    }

    @Test
    fun `empty input is handled`() {
        assertEquals("", PolylineCodec.encodePath(emptyList()))
        assertTrue(PolylineCodec.decodePath("").isEmpty())
        assertTrue(PolylineCodec.decodeValues("").isEmpty())
    }
}
