package com.collectionfield.app.util

import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test
    fun samePointIsZero() {
        assertTrue(GeoMath.distanceMeters(-6.2, 106.8, -6.2, 106.8) < 0.01)
    }

    @Test
    fun jakartaPointsHaveReasonableDistance() {
        val meters = GeoMath.distanceMeters(-6.21462, 106.84513, -6.20876, 106.82011)
        assertTrue(meters in 2_000.0..4_000.0)
    }
}
