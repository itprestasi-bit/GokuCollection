package com.collectionfield.app.util

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager

/**
 * Which way the phone is pointing, as a true-north heading in degrees (0..360).
 *
 * Uses TYPE_ROTATION_VECTOR rather than the raw magnetometer: it is a fused
 * estimate (magnetometer + accelerometer + gyroscope) and is dramatically
 * steadier than reading the compass alone, which swings several degrees from
 * nearby metal, phone speakers, or a motorbike frame.
 *
 * Two corrections matter for the result to line up with the dashboard map:
 *
 *  - **Declination.** The sensor reports magnetic north; Google Maps draws true
 *    north. The offset is about half a degree in Jakarta but reaches ~4 degrees
 *    in Papua, which is a visible error on a rotating icon.
 *  - **Display rotation.** The azimuth is expressed in the device's own frame,
 *    so a phone mounted in landscape reports a heading 90 degrees off unless the
 *    coordinate system is remapped.
 *
 * Emissions are gated by [HeadingFilter]: a value is only handed out when the
 * direction has genuinely turned, or after a quiet period. The sensor itself
 * fires ~50x a second, and transmitting that would flood the live feed for no
 * visible benefit — the eye cannot read 2 degrees of jitter anyway.
 */
class CompassProvider(
    private val context: Context,
    private val onHeading: (Float) -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val filter = HeadingFilter()

    private val rotationMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Set from the last GPS fix so declination can be computed for where we are. */
    @Volatile
    private var declinationDeg: Float = 0f

    @Volatile
    private var running = false

    /** True when this device can report a heading at all. */
    val isSupported: Boolean get() = rotationSensor != null

    fun start() {
        val manager = sensorManager ?: return
        val sensor = rotationSensor ?: return
        if (running) return
        filter.reset()
        // SENSOR_DELAY_UI (~60 ms) rather than FASTEST: the filter throws most
        // samples away regardless, and a faster rate only costs battery.
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        running = true
    }

    fun stop() {
        if (!running) return
        sensorManager?.unregisterListener(this)
        running = false
    }

    /** Feed the latest position so magnetic declination stays locally correct. */
    fun updateLocation(lat: Double, lng: Double, altitudeM: Float = 0f, timeMs: Long = System.currentTimeMillis()) {
        declinationDeg = GeomagneticField(
            lat.toFloat(),
            lng.toFloat(),
            altitudeM,
            timeMs,
        ).declination
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // Remap for how the display is currently rotated, so a phone in a
        // landscape mount doesn't report a heading 90 degrees off.
        val matrix = when (displayRotation()) {
            Surface.ROTATION_90 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped
                )
                remapped
            }
            Surface.ROTATION_180 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped
                )
                remapped
            }
            Surface.ROTATION_270 -> {
                SensorManager.remapCoordinateSystem(
                    rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped
                )
                remapped
            }
            else -> rotationMatrix
        }

        SensorManager.getOrientation(matrix, orientation)
        val magneticDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val trueDeg = HeadingFilter.normalize(magneticDeg + declinationDeg)

        filter.accept(trueDeg, System.currentTimeMillis())?.let(onHeading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Nothing to do: the rotation vector degrades gracefully, and dropping
        // output on a low-accuracy reading would freeze the icon mid-turn.
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    } catch (_: Throwable) {
        Surface.ROTATION_0
    }
}
