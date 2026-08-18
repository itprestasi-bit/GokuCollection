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
            _state.update { it.copy(optimizedRoute = optimized) }
        }
    }
}
