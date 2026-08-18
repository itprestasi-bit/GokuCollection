package com.collectionfield.app.util

import com.collectionfield.app.domain.TrackingMode
import org.junit.Test
import kotlin.random.Random

/**
 * The jitter suppression in LocationRefiner only engages when the caller passes
 * isStationary = true, and that flag comes from MovementClassifier reaching
 * STOPPED. So the question "does the marker stop drifting when parked?" is
 * really a question about whether the classifier ever gets there on real data.
 *
 * A parked phone does not report a clean 0 m/s. GPS-derived speed is computed
 * from successive noisy positions, so standing still typically reads a jittery
 * 0.2-1.5 m/s, occasionally more. This measures how often the classifier
 * actually reports STOPPED under that noise.
 */
class StationaryDetectionTest {

    private fun stoppedShare(peakNoiseMps: Float, seed: Int): Double {
        val classifier = MovementClassifier()
        val rng = Random(seed)
        var stopped = 0
        val n = 600
        repeat(n) {
            // Speed noise is non-negative and skewed low, like a real parked phone.
            val speed = rng.nextFloat() * rng.nextFloat() * peakNoiseMps
            if (classifier.accept(speed) == TrackingMode.STOPPED) stopped++
        }
        return stopped.toDouble() / n
    }

    @Test
    fun `report how often a parked phone is recognised as stationary`() {
        println("=".repeat(58))
        println("  puncak derau kecepatan | dianggap STOPPED")
        println("-".repeat(58))
        for (peak in listOf(0.4f, 0.8f, 1.5f, 3.0f, 5.0f)) {
            val share = (0 until 12).map { stoppedShare(peak, it) }.average()
            println("  %18.1f m/s | %5.1f%%".format(peak, share * 100))
        }
        println("=".repeat(58))
    }
}
