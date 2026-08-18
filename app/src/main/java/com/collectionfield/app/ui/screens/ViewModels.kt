package com.collectionfield.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.collectionfield.app.data.local.OutletEntity
import com.collectionfield.app.data.local.ShiftEntity
import com.collectionfield.app.data.local.TelemetryPointEntity
import com.collectionfield.app.data.local.VisitEntity
import com.collectionfield.app.data.repository.AppContainer
import com.collectionfield.app.domain.CollectorSession
import com.collectionfield.app.domain.VisitPlan
import com.collectionfield.app.util.DeviceState
import com.collectionfield.app.util.GeoMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LoginUiState(
    val firebaseReady: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class LoginViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState(firebaseReady = container.firebaseReady))
    val state: StateFlow<LoginUiState> = _state

    fun login(employeeCode: String, pin: String, onSuccess: (CollectorSession) -> Unit) {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            container.authRepository.login(employeeCode, pin)
                .onSuccess { session ->
                    // Explicit user action and once per session — always pull a fresh
                    // master list here, so "logout lalu login" is the reliable way for
                    // a collector to force a sync when the office says data changed.
                    container.outletRepository.refreshFromCloud(force = true)
                    container.enqueueSync()
                    _state.update { it.copy(isLoading = false, error = null) }
                    onSuccess(session)
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = firebaseErrorMessage(error),
                        )
                    }
                }
        }
    }

    private fun firebaseErrorMessage(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("INVALID_LOGIN_CREDENTIALS", ignoreCase = true) -> "Employee ID atau PIN salah"
            message.contains("password is invalid", ignoreCase = true) -> "Employee ID atau PIN salah"
            message.contains("no user record", ignoreCase = true) -> "Employee ID belum terdaftar"
            message.contains("network", ignoreCase = true) -> "Tidak dapat menghubungi Firebase. Cek internet lalu coba lagi."
            else -> message.ifBlank { "Login gagal" }
        }
    }
}

data class HomeUiState(
    val activeShift: ShiftEntity? = null,
    val latestPoint: TelemetryPointEntity? = null,
    val pendingSync: Int = 0,
    val cloudMessage: String? = null,
    val visitPlan: VisitPlan? = null,
    val currentBatteryPct: Int = -1,
    val currentNetworkState: String = "OFFLINE",
    val openVisit: VisitEntity? = null,
    val openVisitOutlet: OutletEntity? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val container: AppContainer,
    private val session: CollectorSession,
) : ViewModel() {
    private val cloudMessage = MutableStateFlow<String?>(null)
    private val visitPlanFlow = flow {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val plan = container.firestoreService.fetchDailyPlan(session.uid, today).getOrNull()
        emit(plan)
    }
    private val activeShiftFlow = container.shiftRepository.observeActive(session.employeeCode)
    private val openVisitFlow = activeShiftFlow.flatMapLatest { shift ->
        if (shift != null) container.visitRepository.observeAnyOpen(shift.id) else flowOf(null)
    }

    /**
     * The one outlet the open visit belongs to, resolved by id.
     *
     * This used to be `outletRepository.observeActive()` folded into the combine
     * below purely so a `.find {}` could pick one row out of ~7.7k. That made every
     * battery tick, every GPS fix and every sync-count change rebuild and re-diff
     * the whole master list on the main-safe path — the single biggest source of
     * jank and wasted CPU on this screen.
     */
    private val openVisitOutletFlow = openVisitFlow.flatMapLatest { visit ->
        container.outletRepository.observeById(visit?.outletId)
    }

    val state: StateFlow<HomeUiState> = combine(
        activeShiftFlow,
        container.telemetryRepository.observeLatest(session.employeeCode),
        container.telemetryRepository.observePendingCount(session.employeeCode),
        cloudMessage,
        visitPlanFlow,
        DeviceState.batteryFlow(container.appContext),
        DeviceState.networkFlow(container.appContext),
        openVisitFlow,
        openVisitOutletFlow,
    ) { Array ->
        HomeUiState(
            activeShift = Array[0] as ShiftEntity?,
            latestPoint = Array[1] as TelemetryPointEntity?,
            pendingSync = Array[2] as Int,
            cloudMessage = Array[3] as String?,
            visitPlan = Array[4] as VisitPlan?,
            currentBatteryPct = Array[5] as Int,
            currentNetworkState = Array[6] as String,
            openVisit = Array[7] as VisitEntity?,
            openVisitOutlet = Array[8] as OutletEntity?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            container.enqueueSync()
            container.outletRepository.refreshFromCloud()
                .onSuccess { count -> cloudMessage.value = "Master outlet tersinkron: $count" }
                .onFailure { cloudMessage.value = null }
        }
    }

    fun startShift(onStarted: (ShiftEntity) -> Unit) {
        viewModelScope.launch {
            val shift = container.shiftRepository.startShift(session.employeeCode, session.uid)
            onStarted(shift)
        }
    }

    fun endShift(onEnded: () -> Unit) {
        val shift = state.value.activeShift ?: return
        viewModelScope.launch {
            container.shiftRepository.endShift(shift.id)
            onEnded()
        }
    }

    fun logout() = container.authRepository.logout()

    /** Called once the UI has shown [HomeUiState.cloudMessage], so it doesn't repeat. */
    fun clearMessage() {
        cloudMessage.value = null
    }

    /** Fallback for when GPS accuracy is too poor for the automatic geofence to trigger. */
    fun manualCheckIn(outletId: String) {
        val shift = state.value.activeShift ?: return
        val lastPoint = state.value.latestPoint

        // Same rule as the geofence: a visit can only be opened against a stop the
        // office assigned for today. Without this the manual path would be a way
        // around the restriction, which would make the geofence limit cosmetic.
        val plannedIds = state.value.visitPlan?.outlets?.map { it.outletId }.orEmpty()
        if (outletId !in plannedIds) {
            cloudMessage.value = "Outlet ini tidak ada di jadwal kunjungan hari ini."
            return
        }

        viewModelScope.launch {
            if (container.visitRepository.getOpen(shift.id, outletId) != null) return@launch
            container.visitRepository.openVisit(
                id = java.util.UUID.randomUUID().toString(),
                shiftId = shift.id,
                collectorId = session.employeeCode,
                collectorUid = session.uid,
                outletId = outletId,
                arrivalAt = System.currentTimeMillis(),
                method = "MANUAL",
                arrivalLat = lastPoint?.lat,
                arrivalLng = lastPoint?.lng,
            )
            container.enqueueSync()
        }
    }
}

data class OutletListItem(
    val outlet: OutletEntity,
    val distanceM: Double? = null,
)

/**
 * Today's assigned stops, nearest first — not the outlet master list.
 *
 * Two reasons it's scoped this way. It's the only set a collector can actually
 * check in against, so a list of all ~7.7k outlets would mostly be rows they
 * can't act on. And the previous version recomputed a haversine distance for
 * every one of those rows and re-sorted the whole thing on *every* GPS fix,
 * which is the kind of work that shows up as scroll jank and battery drain.
 */
class OutletViewModel(
    private val container: AppContainer,
    private val collectorUid: String,
    private val collectorId: String,
) : ViewModel() {
    private val assignedOutletsFlow = flow {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val plan = container.firestoreService.fetchDailyPlan(collectorUid, today).getOrNull()
        val ids = plan?.outlets?.map { it.outletId }.orEmpty()
        emitAll(container.outletRepository.observeByIds(ids))
    }

    val outlets: StateFlow<List<OutletListItem>> = combine(
        assignedOutletsFlow,
        container.telemetryRepository.observeLatest(collectorId),
    ) { outlets, point ->
        outlets
            .map { outlet ->
                OutletListItem(
                    outlet = outlet,
                    distanceM = point?.takeIf {
                        GeoMath.isValidIndonesiaCoordinate(outlet.lat, outlet.lng)
                    }?.let {
                        GeoMath.distanceMeters(it.lat, it.lng, outlet.lat, outlet.lng)
                    },
                )
            }
            .sortedWith(
                compareBy<OutletListItem> { it.distanceM ?: Double.MAX_VALUE }
                    .thenByDescending { it.outlet.priority },
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

data class HistoryUiState(
    val shifts: List<ShiftEntity> = emptyList(),
    val visits: List<VisitEntity> = emptyList(),
)

class HistoryViewModel(
    container: AppContainer,
    collectorId: String,
) : ViewModel() {
    val state: StateFlow<HistoryUiState> = combine(
        container.shiftRepository.observeRecent(collectorId),
        container.visitRepository.observeRecent(collectorId),
    ) { shifts, visits -> HistoryUiState(shifts, visits) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())
}

@Suppress("UNCHECKED_CAST")
fun <T : ViewModel> simpleViewModelFactory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
    }
