package com.collectionfield.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.collectionfield.app.data.repository.AppContainer
import com.collectionfield.app.domain.VisitOutlet
import com.collectionfield.app.domain.VisitPlan
import com.collectionfield.app.util.GeoMath
import com.collectionfield.app.util.RouteOptimizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class DailyPlanUiState(
    val isLoading: Boolean = false,
    val plan: VisitPlan? = null,
    val optimizedRoute: List<VisitOutlet> = emptyList(),
    /** Road geometry from the Routes API. Empty means the map falls back to straight lines. */
    val routePath: List<Pair<Double, Double>> = emptyList(),
    val routeDistanceM: Double = 0.0,
    val routeDurationSec: Long = 0L,
    val error: String? = null
)

class DailyPlanViewModel(
    private val container: AppContainer,
    private val collectorUid: String
) : ViewModel() {

    private val _state = MutableStateFlow(DailyPlanUiState())
    val state: StateFlow<DailyPlanUiState> = _state

    fun fetchTodayPlan() {
        viewModelScope.launch { loadTodayPlan() }
    }

    private suspend fun loadTodayPlan(): VisitPlan? {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        _state.update { it.copy(isLoading = true, error = null) }

        return container.firestoreService.fetchDailyPlan(collectorUid, today)
            .onSuccess { plan -> _state.update { it.copy(isLoading = false, plan = plan) } }
            .onFailure { error -> _state.update { it.copy(isLoading = false, error = error.message) } }
            .getOrNull()
    }

    /**
     * Reachable directly from the home screen's "Rute Hari Ini" button, bypassing
     * the daily-plan list screen — so [state.plan] may still be null here (each nav
     * route gets its own ViewModel instance). Fetch it first when that's the case.
     */
    fun optimizeRoute(currentLat: Double, currentLng: Double) {
        viewModelScope.launch {
            val plan = _state.value.plan ?: loadTodayPlan()
            // Outlets with bad coordinates (0,0 sentinel or otherwise outside
            // Indonesia — a known data-quality issue in the source import) are kept
            // in the plan list so the collector can still see and manually check in
            // to them, but excluded here so they can't drag the route toward the
            // ocean or corrupt the nearest-neighbor ordering.
            val pendingOutlets = plan?.outlets
                ?.filter { it.status == "PENDING" }
                ?.filter { GeoMath.isValidIndonesiaCoordinate(it.latitude, it.longitude) }
                ?: emptyList()
            val optimized = RouteOptimizer.optimize(currentLat, currentLng, pendingOutlets)
            _state.update { it.copy(optimizedRoute = optimized, routePath = emptyList()) }
            if (optimized.isEmpty()) return@launch

            // Ask the server for the actual roads. The local optimiser is a greedy
            // nearest-neighbour walk that knows nothing about one-way streets, turn
            // restrictions or rivers, so when the server reorders the stops its
            // answer replaces ours — and its polyline is the route the collector
            // will really ride rather than a line drawn over the top of the city.
            val route = container.cloudDataSource?.planRoute(
                origin = currentLat to currentLng,
                stops = optimized.map { it.latitude to it.longitude },
            ) ?: return@launch

            val reordered = if (route.order.size == optimized.size - 1) {
                // The server orders the intermediates only; the final stop stays put.
                route.order.mapNotNull { optimized.getOrNull(it) } + optimized.last()
            } else {
                optimized
            }

            _state.update {
                it.copy(
                    optimizedRoute = reordered.mapIndexed { i, o -> o.copy(urutanRute = i + 1) },
                    routePath = route.points,
                    routeDistanceM = route.distanceMeters,
                    routeDurationSec = route.durationSeconds,
                )
            }
        }
    }
}
