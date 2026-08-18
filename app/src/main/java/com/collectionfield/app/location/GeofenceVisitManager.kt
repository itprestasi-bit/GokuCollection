package com.collectionfield.app.location

import com.collectionfield.app.data.local.OutletEntity
import com.collectionfield.app.data.repository.VisitRepository
import com.collectionfield.app.util.GeoMath
import java.util.UUID

/**
 * Detects outlet arrival/departure purely from GPS fixes already being collected
 * during a shift — no action required from the collector. A visit is opened the
 * moment a fix lands inside an outlet's radius_m, and closed once the collector has
 * been consistently outside radius_m + EXIT_BUFFER_M for EXIT_CONFIRM_FIXES fixes in a
 * row (hysteresis so GPS jitter right at the boundary doesn't flap the visit open/closed).
 *
 * Scope: **only the outlets assigned for today**, supplied via [setAssignedOutlets].
 * That is a rule, not an optimisation — a collector checks in against the stops the
 * office planned for them, so happening to drive past an unrelated outlet must not
 * open a visit. It also happens to be what makes this cheap: the previous version
 * measured the distance to every one of ~7.7k outlets on every fix (as often as
 * every 3 seconds while moving), which is roughly 2,500 haversine computations a
 * second on the phone's battery. A day's route is a dozen or so.
 *
 * With no assignment for today the manager simply never opens a visit; the
 * collector can still record one through the manual check-in fallback.
 */
class GeofenceVisitManager(
    private val visitRepository: VisitRepository,
) {
    @Volatile
    private var assignedOutlets: List<OutletEntity> = emptyList()
    private val exitStreak = mutableMapOf<String, Int>()

    /** Replaces the geofence set. Safe to call mid-shift when the plan changes. */
    fun setAssignedOutlets(outlets: List<OutletEntity>) {
        assignedOutlets = outlets
        // Drop hysteresis state for outlets that are no longer on the route, so a
        // stale streak can't close a visit belonging to a since-removed stop.
        val ids = outlets.mapTo(HashSet()) { it.id }
        exitStreak.keys.retainAll(ids)
    }

    /** True if any of today's stops currently has an open (auto or manual) visit. */
    suspend fun onLocation(
        shiftId: String,
        collectorId: String,
        collectorUid: String,
        lat: Double,
        lng: Double,
        lowConfidence: Boolean,
    ): Boolean {
        if (lowConfidence) return visitRepository.getAnyOpen(shiftId) != null

        val outlets = assignedOutlets
        if (outlets.isEmpty()) return visitRepository.getAnyOpen(shiftId) != null

        var anyOpen = false
        for (outlet in outlets) {
            if (!GeoMath.isValidIndonesiaCoordinate(outlet.lat, outlet.lng)) continue

            val distance = GeoMath.distanceMeters(lat, lng, outlet.lat, outlet.lng)
            val open = visitRepository.getOpen(shiftId, outlet.id)

            when {
                open == null && distance <= outlet.radiusM -> {
                    visitRepository.openVisit(
                        id = UUID.randomUUID().toString(),
                        shiftId = shiftId,
                        collectorId = collectorId,
                        collectorUid = collectorUid,
                        outletId = outlet.id,
                        arrivalAt = System.currentTimeMillis(),
                        method = "AUTO_GEOFENCE",
                        arrivalLat = lat,
                        arrivalLng = lng,
                    )
                    exitStreak[outlet.id] = 0
                    anyOpen = true
                }
                open != null && distance > outlet.radiusM + EXIT_BUFFER_M -> {
                    val streak = (exitStreak[outlet.id] ?: 0) + 1
                    exitStreak[outlet.id] = streak
                    if (streak >= EXIT_CONFIRM_FIXES) {
                        val departureAt = System.currentTimeMillis()
                        val durationSec = ((departureAt - open.arrivalAt) / 1000).coerceAtLeast(0)
                        visitRepository.closeVisit(open.id, departureAt, durationSec)
                        exitStreak.remove(outlet.id)
                    } else {
                        anyOpen = true
                    }
                }
                open != null -> {
                    exitStreak[outlet.id] = 0
                    anyOpen = true
                }
            }
        }
        return anyOpen
    }

    companion object {
        private const val EXIT_BUFFER_M = 15
        private const val EXIT_CONFIRM_FIXES = 2
    }
}
