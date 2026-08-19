package com.collectionfield.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android is allowed to put this app to sleep.
 *
 * A foreground service survives being backgrounded, but it does not survive the
 * battery optimiser on the handsets collectors actually carry. Measured on real
 * shifts before this was addressed: an 82-minute shift recorded one GPS point,
 * and a still-running shift went 107 minutes between two consecutive points.
 * The tracking was not crashing — the phone was suspending it, and the trail the
 * replay screen plays back simply never got written.
 *
 * Exempting the app is a user decision that only the system dialog can grant, so
 * the app can ask but never assume. Everything here degrades safely: on a device
 * where the direct request is unavailable, [settingsIntent] opens the list the
 * collector can toggle by hand.
 */
object BatteryOptimization {

    /** True when Android will leave the app running in the background. */
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * The system's own confirmation dialog. Returns null when no activity can
     * handle it (some OEM builds strip it), so callers fall back to
     * [settingsIntent] rather than crashing on an unresolvable intent.
     */
    @Suppress("BatteryLife") // Continuous location tracking for a work shift is the app's purpose.
    fun requestIntent(context: Context): Intent? {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    /** The full battery-optimisation list, as a fallback. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
