package com.collectionfield.app.util

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Google's polyline encoding, used here to store a shift's GPS trail as a few
 * strings instead of thousands of documents.
 *
 * The arithmetic is not the interesting part — the reason it is worth having is:
 * a 2-second trail over an eight-hour shift is 14,400 fixes. As one Firestore
 * document each that is 14,400 writes to record and 14,400 reads to replay, which
 * is both slow and the largest line on the bill. Encoded, the same trail is about
 * 100 KB of text: a handful of documents to write, one or two to read back.
 *
 * The format encodes *differences* between consecutive values as variable-length
 * integers, so a slow-moving stream costs a couple of bytes per point. That works
 * for anything that changes gradually, not just coordinates — timestamps and
 * speeds ride along the same way, in their own streams, so the replay keeps real
 * times and real speeds rather than interpolating them back.
 */
object PolylineCodec {

    /** Coordinates, at Google's standard 1e-5 degree precision (about 1 m). */
    fun encodePath(points: List<Pair<Double, Double>>): String {
        val out = StringBuilder()
        var prevLat = 0L
        var prevLng = 0L
        for ((lat, lng) in points) {
            val e5Lat = (lat * 1e5).roundToLong()
            val e5Lng = (lng * 1e5).roundToLong()
            encodeSigned(e5Lat - prevLat, out)
            encodeSigned(e5Lng - prevLng, out)
            prevLat = e5Lat
            prevLng = e5Lng
        }
        return out.toString()
    }

    fun decodePath(encoded: String): List<Pair<Double, Double>> {
        val result = mutableListOf<Pair<Double, Double>>()
        var index = 0
        var lat = 0L
        var lng = 0L
        while (index < encoded.length) {
            val (dLat, next1) = decodeSigned(encoded, index)
            val (dLng, next2) = decodeSigned(encoded, next1)
            lat += dLat
            lng += dLng
            index = next2
            result += (lat / 1e5) to (lng / 1e5)
        }
        return result
    }

    /** Any integer series — used for time offsets (seconds) and speeds (dm/s). */
    fun encodeValues(values: List<Long>): String {
        val out = StringBuilder()
        var prev = 0L
        for (v in values) {
            encodeSigned(v - prev, out)
            prev = v
        }
        return out.toString()
    }

    fun decodeValues(encoded: String): List<Long> {
        val result = mutableListOf<Long>()
        var index = 0
        var current = 0L
        while (index < encoded.length) {
            val (delta, next) = decodeSigned(encoded, index)
            current += delta
            index = next
            result += current
        }
        return result
    }

    /** Speed in metres per second, stored as decimetres to keep one decimal. */
    fun speedsToDeci(speeds: List<Float>): List<Long> = speeds.map { (it * 10f).roundToInt().toLong() }

    fun deciToSpeeds(values: List<Long>): List<Float> = values.map { it / 10f }

    private fun encodeSigned(value: Long, out: StringBuilder) {
        // Zig-zag: the sign becomes the low bit, so small negatives stay small.
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        out.append((v.toInt() + 63).toChar())
    }

    private fun decodeSigned(encoded: String, start: Int): Pair<Long, Int> {
        var index = start
        var shift = 0
        var result = 0L
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
        } while (b >= 0x20)
        val value = if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
        return value to index
    }
}
