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
                    container.outletRepository.refreshFromCloud()
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

    val state: StateFlow<HomeUiState> = combine(
        activeShiftFlow,
        container.telemetryRepository.observeLatest(session.employeeCode),
        container.telemetryRepository.observePendingCount(session.employeeCode),
        cloudMessage,
        visitPlanFlow,
        DeviceState.batteryFlow(container.appContext),
        DeviceState.networkFlow(container.appContext),
        openVisitFlow,
        container.outletRepository.observeActive(),
    ) { Array ->
        val openVisit = Array[7] as VisitEntity?
        val outlets = Array[8] as List<OutletEntity>
        HomeUiState(
            activeShift = Array[0] as ShiftEntity?,
            latestPoint = Array[1] as TelemetryPointEntity?,
            pendingSync = Array[2] as Int,
            cloudMessage = Array[3] as String?,
            visitPlan = Array[4] as VisitPlan?,
            currentBatteryPct = Array[5] as Int,
            currentNetworkState = Array[6] as String,
            openVisit = openVisit,
            openVisitOutlet = outlets.find { it.id == openVisit?.outletId },
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

    /** Fallback for when GPS accuracy is too poor for the automatic geofence to trigger. */
    fun manualCheckIn(outletId: String) {
        val shift = state.value.activeShift ?: return
        val lastPoint = state.value.latestPoint
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

class OutletViewModel(
    container: AppContainer,
    collectorId: String,
) : ViewModel() {
    val outlets: StateFlow<List<OutletListItem>> = combine(
        container.outletRepository.observeActive(),
        container.telemetryRepository.observeLatest(collectorId),
    ) { outlets, point ->
        outlets.map { outlet ->
            OutletListItem(
                outlet = outlet,
                distanceM = point?.let {
                    GeoMath.distanceMeters(it.lat, it.lng, outlet.lat, outlet.lng)
                },
            )
        }.sortedWith(compareBy<OutletListItem> { it.distanceM ?: Double.MAX_VALUE }.thenByDescending { it.outlet.priority })
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
