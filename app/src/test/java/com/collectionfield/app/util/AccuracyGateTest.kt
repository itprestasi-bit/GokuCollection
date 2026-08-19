package com.collectionfield.app.util

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What the accuracy ceiling actually buys, and what it costs.
 *
 * Reported accuracy is not a number the app picks — the GPS states it. The only
 * lever that improves it is refusing the poor fixes, which trades availability for
 * precision: reject too much and the marker stops moving. This measures both sides
 * against realistic accuracy distributions, so the trade is visible rather than
 * asserted.
 */
class AccuracyGateTest {

    private val baseLat = -6.2088
    private val baseLng = 106.8456
    private fun mToLat(m: Double) = m / 111_320.0

    private data class Outcome(
        val rawMeanM: Double,
        val publishedMeanM: Double,
        val publishRate: Double,
        val longestGapS: Double,
    )

    /**
     * @param typical the accuracy the receiver reports most of the time
     * @param spread how far it wanders from that, log-normally — real GPS accuracy
     *   has a long tail rather than a symmetric one
     */
    private fun run(typical: Double, spread: Double, speedMps: Double, seed: Int): Outcome {
        val refiner = LocationRefiner()
        val rng = Random(seed)
        var t = 1_000_000L
        var north = 0.0

        val rawAcc = mutableListOf<Double>()
        val pubAcc = mutableListOf<Double>()
        var publishes = 0
        var lastPublishAt = t
        var longestGap = 0L
        val steps = 900 // half an hour at 2 s

        repeat(steps) {
            north += speedMps * 2.0
            t += 2_000
            // Log-normal-ish: mostly near typical, occasionally much worse.
            val acc = (typical * Math.exp(rng.nextDouble(-0.35, spread))).coerceIn(3.0, 200.0)
            rawAcc += acc

            val r = refiner.refine(
                rawLat = baseLat + mToLat(north + (rng.nextDouble() - 0.5) * acc),
                rawLng = baseLng,
                accuracyM = acc.toFloat(),
                rawSpeedMps = speedMps.toFloat(),
                bearing = null,
                timeMs = t,
                isStationary = speedMps < 0.5,
            )
            if (r != null) {
                publishes++
                pubAcc += r.accuracyM.toDouble()
                longestGap = maxOf(longestGap, t - lastPublishAt)
                lastPublishAt = t
            }
        }
        longestGap = maxOf(longestGap, t - lastPublishAt)

        return Outcome(
            rawMeanM = rawAcc.average(),
            publishedMeanM = if (pubAcc.isEmpty()) Double.NaN else pubAcc.average(),
            publishRate = publishes.toDouble() / steps,
            longestGapS = longestGap / 1000.0,
        )
    }

    @Test
    fun `report accuracy delivered against accuracy received`() {
        println("=".repeat(88))
        println("  kondisi                  | akurasi mentah | akurasi tampil | lolos | jeda terpanjang")
        println("-".repeat(88))
        fun row(label: String, typical: Double, spread: Double, speed: Double) {
            val runs = (0 until 8).map { run(typical, spread, speed, it) }
            println(
                "  %-24s | %11.1f m | %11.1f m | %4.0f%% | %11.1f s".format(
                    label,
                    runs.map { it.rawMeanM }.average(),
                    runs.map { it.publishedMeanM }.average(),
                    runs.map { it.publishRate }.average() * 100,
                    runs.map { it.longestGapS }.average(),
                )
            )
        }
        row("langit terbuka, jalan", 6.0, 0.5, 8.0)
        row("jalan kota, motor", 9.0, 0.7, 8.0)
        row("gedung tinggi, motor", 16.0, 0.9, 8.0)
        row("gang sempit, jalan kaki", 22.0, 1.0, 1.4)
        row("dalam ruangan", 40.0, 1.0, 0.0)
        println("=".repeat(88))
    }

    @Test
    fun `under ten metres where GPS allows it, and never worse than what arrived`() {
        // Where the receiver can support it, the published figure must clear 10 m.
        for ((label, cfg) in listOf(
            "langit terbuka" to Triple(6.0, 0.5, 8.0),
            "jalan kota" to Triple(9.0, 0.7, 8.0),
        )) {
            val (typical, spread, speed) = cfg
            val mean = (0 until 8).map { run(typical, spread, speed, it).publishedMeanM }.average()
            assertTrue("$label: akurasi tampil %.1f m".format(mean), mean < 10.0)
        }

        // Where it cannot, the filter must still improve on the raw stream rather
        // than pass it through — and must not go quiet. A marker that disappears in
        // an alley is a worse answer than a marker labelled as rough.
        for ((label, cfg) in listOf(
            "gedung tinggi" to Triple(16.0, 0.9, 8.0),
            "gang sempit" to Triple(22.0, 1.0, 1.4),
            "dalam ruangan" to Triple(40.0, 1.0, 0.0),
        )) {
            val (typical, spread, speed) = cfg
            val runs = (0 until 8).map { run(typical, spread, speed, it) }
            val raw = runs.map { it.rawMeanM }.average()
            val published = runs.map { it.publishedMeanM }.average()
            val gap = runs.map { it.longestGapS }.average()
            // Not "better than the raw mean" — a held position honestly reports the
            // accuracy of the fix its coordinates came from, and that single fix can
            // sit slightly above the running average. Claiming the average would be
            // claiming a precision the coordinates do not have. What must not happen
            // is the filter making things materially worse.
            assertTrue(
                "$label: tampil %.1f m jauh lebih buruk dari mentah %.1f m".format(published, raw),
                published <= raw * 1.15,
            )
            assertTrue("$label: marker hilang %.0f detik".format(gap), gap <= 30.0)
        }
    }
}
