package com.collectionfield.app.util

import com.collectionfield.app.domain.VisitOutlet

object RouteOptimizer {
    /**
     * Optimizes a list of outlets using a Nearest Neighbor algorithm starting from [startLat], [startLng].
     */
    fun optimize(startLat: Double, startLng: Double, outlets: List<VisitOutlet>): List<VisitOutlet> {
        if (outlets.isEmpty()) return emptyList()

        val unvisited = outlets.toMutableList()
        val optimized = mutableListOf<VisitOutlet>()

        var currentLat = startLat
        var currentLng = startLng

        repeat(unvisited.size) {
            val nearest = unvisited.minByOrNull { outlet ->
                GeoMath.distanceMeters(currentLat, currentLng, outlet.latitude, outlet.longitude)
            }
            
            nearest?.let {
                optimized.add(it)
                unvisited.remove(it)
                currentLat = it.latitude
                currentLng = it.longitude
            }
        }
        
        return optimized
    }
}
