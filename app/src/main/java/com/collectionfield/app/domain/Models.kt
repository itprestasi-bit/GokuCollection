package com.collectionfield.app.domain

enum class ShiftStatus { ACTIVE, ENDED }
enum class SyncStatus { PENDING, SYNCING, SYNCED, FAILED }
enum class TrackingMode { MOVING, SLOW, STOPPED }
enum class UserRole { COLLECTOR, SUPERVISOR, ADMIN }

data class CollectorSession(
    val uid: String,
    val employeeCode: String,
    val displayName: String,
    val role: UserRole,
    val branchId: String? = null,
    val teamId: String? = null,
)

/**
 * How hard to ask the OS for location, per movement mode.
 *
 * [minDistanceM] is the cheapest saving available: the OS drops fixes below that
 * displacement itself, so the app process is never even woken. Everything else in
 * the pipeline runs only on fixes that survive this filter.
 *
 * Every mode asks for full satellite accuracy. An earlier version dropped the
 * parked mode to PRIORITY_BALANCED_POWER_ACCURACY to save the GPS radio, on the
 * reasoning that coarse wifi/cell positioning is enough to say "still at this
 * outlet". That reasoning was wrong in the one place it mattered: parked at a
 * customer is exactly when the 30 m check-in geofence is being evaluated, and
 * wifi/cell positioning reports 50-2000 m. The mode meant to be cheap was the
 * mode deciding whether a visit counts — and it was reporting +-100 m to do it.
 *
 * So the battery lever is [intervalMs] alone: 30 s parked against 5 s moving is
 * six times fewer fixes, [minDistanceM] discards most of what is left before the
 * process even wakes, and TelemetryGate decides separately what gets uploaded.
 */
data class TrackingPolicy(
    val intervalMs: Long,
    val minIntervalMs: Long,
    val minDistanceM: Float,
) {
    companion object {
        // 2 s in motion, and no OS-level distance filter in either moving mode.
        // The filter is what made the interval a fiction at low speed: a collector
        // walking at 1.4 m/s needs 7 s to cover 10 m, so the OS simply withheld the
        // fixes in between and the marker updated on distance, not on time. What
        // actually protects battery and data is further down the pipeline — the
        // jitter hold refuses to move the marker on noise, and TelemetryGate
        // decides what is worth transmitting — and neither needs the OS to
        // withhold fixes to do its job.
        val Moving = TrackingPolicy(2_000L, 2_000L, 0f)
        val Slow = TrackingPolicy(2_000L, 2_000L, 0f)
        // Parked samples at the same rate. It used to ask every 30 s with a 10 m
        // filter, which read as thrifty and behaved as broken: a collector who set
        // off was not sampled again for up to half a minute, so the dashboard
        // showed them standing still long after they had gone. Sampling is not what
        // costs quota — transmitting is, and TelemetryGate still suppresses that to
        // a heartbeat while parked. The price paid here is battery, deliberately.
        val Stopped = TrackingPolicy(2_000L, 2_000L, 0f)
    }
}
