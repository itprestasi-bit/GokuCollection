package com.collectionfield.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shifts",
    indices = [Index("collectorId"), Index("collectorUid"), Index("status"), Index("syncStatus")],
)
data class ShiftEntity(
    @PrimaryKey val id: String,
    val collectorId: String,
    val collectorUid: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String,
    val firstLat: Double? = null,
    val firstLng: Double? = null,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val syncStatus: String,
)

@Entity(
    tableName = "telemetry_points",
    indices = [Index("shiftId"), Index("collectorId"), Index("collectorUid"), Index("syncStatus"), Index("capturedAt")],
)
data class TelemetryPointEntity(
    @PrimaryKey val id: String,
    val shiftId: String,
    val collectorId: String,
    val collectorUid: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val speedMps: Float,
    val bearing: Float,
    val capturedAt: Long,
    val receivedAt: Long? = null,
    val batteryPct: Int,
    val networkState: String,
    val mockFlag: Boolean,
    val syncStatus: String,
)

@Entity(
    tableName = "outlets",
    indices = [Index(value = ["code"], unique = true), Index("status")],
)
data class OutletEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val address: String,
    val radiusM: Int = 30,
    val priority: Int = 1,
    val status: String = "ACTIVE",
    val totalPiutang: Double = 0.0,
    val jatuhTempo: String? = null,
    // JSON-encoded map of tag -> {amount, dueDate}, e.g. {"MCO":{"amount":500000,"dueDate":"2026-08-20"}}.
    // Superseding totalPiutang/jatuhTempo, kept above for schema compatibility but no
    // longer written to — a debtor's receivables are tracked per tag now.
    val piutangJson: String? = null,
)

@Entity(
    tableName = "visits",
    indices = [Index("shiftId"), Index("collectorId"), Index("collectorUid"), Index("outletId"), Index("syncStatus")],
)
data class VisitEntity(
    @PrimaryKey val id: String,
    val shiftId: String,
    val collectorId: String,
    val collectorUid: String,
    val outletId: String,
    val arrivalAt: Long,
    val arrivalLat: Double? = null,
    val arrivalLng: Double? = null,
    val departureAt: Long? = null,
    val durationSec: Long? = null,
    val result: String? = null,
    val method: String = "MANUAL",
    val confidence: Double = 1.0,
    val notes: String? = null,
    val photoUrl: String? = null,
    val syncStatus: String,
)
