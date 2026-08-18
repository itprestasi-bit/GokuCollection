package com.collectionfield.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class HeadingFilterTest {

    @Test
    fun `shortest delta takes the near way round the circle`() {
        // The whole reason angles cannot be handled arithmetically: 350 -> 10 is a
        // 20 degree turn to the right, not a 340 degree turn to the left.
        assertEquals(20f, HeadingFilter.shortestDelta(350f, 10f), 0.001f)
        assertEquals(-20f, HeadingFilter.shortestDelta(10f, 350f), 0.001f)
        assertEquals(90f, HeadingFilter.shortestDelta(0f, 90f), 0.001f)
        assertEquals(180f, abs(HeadingFilter.shortestDelta(0f, 180f)), 0.001f)
    }

    @Test
    fun `normalize wraps negatives and overflow into 0-360`() {
        assertEquals(350f, HeadingFilter.normalize(-10f), 0.001f)
        assertEquals(10f, HeadingFilter.normalize(370f), 0.001f)
        assertEquals(0f, HeadingFilter.normalize(720f), 0.001f)
    }

    @Test
    fun `smoothing near north does not swing to the far side`() {
        // Samples straddling 0 degrees. A naive mean would land near 180 — pointing
        // the icon exactly backwards.
        val filter = HeadingFilter(minChangeDeg = 0f, maxQuietMs = 0L, smoothingAlpha = 0.5f)
        var t = 0L
        listOf(358f, 2f, 359f, 1f, 0f).forEach { filter.accept(it, t.also { t += 100 }) }

        val result = filter.smoothed!!
        val offNorth = abs(HeadingFilter.shortestDelta(0f, result))
        assertTrue("hasil $result derajat — smoothing melingkar gagal di sekitar 0", offNorth < 5f)
    }

    @Test
    fun `a steady heading stays quiet instead of streaming`() {
        val filter = HeadingFilter(minChangeDeg = 12f, maxQuietMs = 10_000L)
        var t = 0L
        assertNotNull("sampel pertama harus keluar", filter.accept(90f, t))

        // 100 samples of typical magnetometer jitter over 5 seconds.
        val rng = Random(7)
        var emissions = 0
        repeat(100) {
            t += 50
            if (filter.accept(90f + (rng.nextFloat() - 0.5f) * 6f, t) != null) emissions++
        }
        assertEquals("getaran sensor tidak boleh memicu kiriman", 0, emissions)
    }

    @Test
    fun `a real turn is reported`() {
        val filter = HeadingFilter(minChangeDeg = 12f, maxQuietMs = 60_000L, smoothingAlpha = 1f)
        var t = 0L
        filter.accept(0f, t)

        t += 1000
        assertNull("belok 5 derajat masih derau", filter.accept(5f, t))

        t += 1000
        assertNotNull("belok 45 derajat harus dilaporkan", filter.accept(45f, t))
    }

    @Test
    fun `emits again after the quiet window even without turning`() {
        val filter = HeadingFilter(minChangeDeg = 12f, maxQuietMs = 10_000L, smoothingAlpha = 1f)
        filter.accept(180f, 0L)
        assertNull(filter.accept(180f, 5_000L))
        assertNotNull("harus menyegarkan setelah jendela diam", filter.accept(180f, 10_001L))
    }

    /**
     * Not an assertion — the traffic estimate behind the bandwidth figures, so the
     * numbers quoted can be checked rather than trusted.
     */
    @Test
    fun `estimate emissions per hour for realistic behaviour`() {
        // Mirrors LocationTrackingService.onHeading: a second gate on real
        // rotation, which is what actually decides network traffic. The filter's
        // own periodic emission keeps the local value fresh but must not write.
        val pushDegrees = 20f
        val pushMinIntervalMs = 3_000L

        fun run(label: String, samples: Int, headingAt: (Int) -> Float) {
            val filter = HeadingFilter()
            var t = 0L
            var emitted = 0
            var pushed = 0
            var lastPushed: Float? = null
            var lastPushAt = 0L
            repeat(samples) { i ->
                t += 50 // sensor at ~20 Hz
                val h = filter.accept(headingAt(i), t) ?: return@repeat
                emitted++
                val turned = lastPushed == null ||
                    abs(HeadingFilter.shortestDelta(lastPushed!!, h)) >= pushDegrees
                if (turned && t - lastPushAt >= pushMinIntervalMs) {
                    lastPushed = h
                    lastPushAt = t
                    pushed++
                }
            }
            val hours = (samples * 50L) / 3_600_000.0
            println(
                "  %-28s emisi %5.0f/jam   KIRIM RTDB %5.0f/jam".format(
                    label, emitted / hours, pushed / hours
                )
            )
        }

        val rng = Random(11)
        println("=".repeat(56))
        // 20 Hz for 10 minutes = 12,000 samples.
        run("HP diam di meja", 12_000) { 90f + (rng.nextFloat() - 0.5f) * 4f }
        run("jalan kaki, belok sesekali", 12_000) { i -> (i / 200) * 30f + (rng.nextFloat() - 0.5f) * 8f }
        run("berputar terus (kasus terburuk)", 12_000) { i -> (i * 0.5f) % 360f }
        println("=".repeat(56))
    }
}
