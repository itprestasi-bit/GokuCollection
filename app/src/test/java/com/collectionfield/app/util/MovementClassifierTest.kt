package com.collectionfield.app.util

import com.collectionfield.app.domain.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class MovementClassifierTest {
    @Test
    fun changesModeOnlyAfterThreeConsecutiveSamples() {
        val classifier = MovementClassifier()
        assertEquals(TrackingMode.MOVING, classifier.accept(0.1f))
        assertEquals(TrackingMode.MOVING, classifier.accept(0.1f))
        assertEquals(TrackingMode.STOPPED, classifier.accept(0.1f))
    }
}
