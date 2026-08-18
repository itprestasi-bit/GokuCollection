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
 * [highAccuracy] exists because the GPS radio is the single biggest battery draw
 * here. While the collector is parked at a customer there is nothing to learn
 * from satellite-grade precision — coarse wifi/cell positioning is enough to say
 * "still at this outlet", and it costs a fraction of the power. It flips back on
 * the moment they start moving again.
 */
data class TrackingPolicy(
    val intervalMs: Long,
    val minIntervalMs: Long,
    val minDistanceM: Float,
    val highAccuracy: Boolean,
) {
    companion object {
        // 5 s while moving keeps the dashboard marker fluid; 10 m is the movement
        // threshold, applied here at the OS level as well as before upload.
        val Moving = TrackingPolicy(5_000L, 5_000L, 10f, highAccuracy = true)
        val Slow = TrackingPolicy(10_000L, 8_000L, 10f, highAccuracy = true)
        // Parked: ask rarely and cheaply. Suppressing the repeat updates is
        // handled by TelemetryGate, which is what actually decides to transmit.
        val Stopped = TrackingPolicy(30_000L, 20_000L, 10f, highAccuracy = false)
    }
}
