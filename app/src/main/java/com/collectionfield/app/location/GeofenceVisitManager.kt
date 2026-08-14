package com.collectionfield.app.location

import com.collectionfield.app.data.local.OutletEntity
import com.collectionfield.app.data.repository.OutletRepository
import com.collectionfield.app.data.repository.VisitRepository
import com.collectionfield.app.util.GeoMath
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Detects outlet arrival/departure purely from GPS fixes already being collected
 * during a shift — no action required from the collector. A visit is opened the
 * moment a fix lands inside an outlet's radius_m, and closed once the collector has
 * been consistently outside radius_m + EXIT_BUFFER_M for EXIT_CONFIRM_FIXES fixes in a
 * row (hysteresis so GPS jitter right at the boundary doesn't flap the visit open/closed).
 */
class GeofenceVisitManager(
    private val outletRepository: OutletRepository,
    private val visitRepository: VisitRepository,
) {
    private var cachedOutlets: List<OutletEntity> = emptyList()
    private val exitStreak = mutableMapOf<String, Int>()

    /** Returns true if the collector currently has at least one open (auto or manual) visit. */
    suspend fun onLocation(
        shiftId: String,
        collectorId: String,
        collectorUid: String,
        lat: Double,
        lng: Double,
        lowConfidence: Boolean,
    ): Boolean {
        if (lowConfidence) return visitRepository.getAnyOpen(shiftId) != null
        if (cachedOutlets.isEmpty()) cachedOutlets = outletRepository.observeActive().first()

        var anyOpen = false
        for (outlet in cachedOutlets) {
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
