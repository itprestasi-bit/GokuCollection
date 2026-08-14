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

data class TrackingPolicy(
    val intervalMs: Long,
    val minIntervalMs: Long,
    val minDistanceM: Float,
) {
    companion object {
        val Moving = TrackingPolicy(3_000L, 3_000L, 5f)
        val Slow = TrackingPolicy(25_000L, 15_000L, 5f)
        val Stopped = TrackingPolicy(60_000L, 30_000L, 5f)
    }
}
