package com.collectionfield.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.collectionfield.app.data.repository.AppContainer
import com.collectionfield.app.util.ImageCompressor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VisitUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
)

/**
 * Attaches the human-entered outcome (photo/notes/payment status) onto a visit
 * that was already opened automatically by [com.collectionfield.app.location.GeofenceVisitManager]
 * (or manually as a fallback) — this never creates a new visit document itself.
 */
class VisitActionViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(VisitUiState())
    val state: StateFlow<VisitUiState> = _state

    fun submitVisitResult(
        context: Context,
        visitId: String,
        outletId: String,
        collectorUid: String,
        catatan: String,
        photo: Bitmap,
        newStatus: String,
        tag: String,
        remainingDebt: Double? = null,
    ) {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null, success = false) }

        viewModelScope.launch {
            try {
                val compressedFile = ImageCompressor.compressBitmap(context, photo)

                val photoUrl = container.storageService.uploadVisitPhoto(collectorUid, visitId, compressedFile)
                    .getOrThrow()

                container.visitRepository.updateResult(
                    id = visitId,
                    notes = catatan,
                    result = newStatus,
                    photoUrl = photoUrl,
                )

                // Manual fallback visits have no GPS-based exit signal, so submitting
                // the result is also what closes them (duration = time spent filling this in).
                val visit = container.visitRepository.getById(visitId)
                if (visit != null && visit.method == "MANUAL" && visit.departureAt == null) {
                    val departureAt = System.currentTimeMillis()
                    val durationSec = ((departureAt - visit.arrivalAt) / 1000).coerceAtLeast(0)
                    container.visitRepository.closeVisit(visitId, departureAt, durationSec)
                }

                // Queue the visit before touching the outlet. The outlet balance is a
                // secondary, derivable write; the visit is the collector's actual work
                // and is already committed locally by this point. When the outlet
                // update failed — as it did for every collector until the rules let
                // them record a payment at all — the exception skipped this line, so
                // the visit sat in Room unqueued and the collector was shown an error
                // for something that had in fact been saved.
                container.enqueueSync()

                container.cloudDataSource?.updateOutletDebt(
                    outletId = outletId,
                    tag = tag,
                    status = newStatus,
                    remainingDebt = remainingDebt,
                )

                _state.update { it.copy(isLoading = false, success = true) }
                compressedFile.delete()
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Terjadi kesalahan") }
            }
        }
    }

    fun resetState() {
        _state.update { VisitUiState() }
    }
}
