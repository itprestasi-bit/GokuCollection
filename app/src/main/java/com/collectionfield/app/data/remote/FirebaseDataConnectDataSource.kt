package com.collectionfield.app.data.remote

import com.collectionfield.app.data.local.OutletEntity
import com.collectionfield.app.data.local.ShiftEntity
import com.collectionfield.app.data.local.TelemetryPointEntity
import com.collectionfield.app.data.local.VisitEntity
import com.collectionfield.app.dataconnect.*
import com.google.firebase.Timestamp
import java.util.Date

class FirebaseDataConnectDataSource(
    val connector: DefaultConnector = DefaultConnector.instance
) {
    suspend fun getUser(uid: String) = connector.getUser.execute(uid).data.user

    suspend fun upsertUser(uid: String, employeeCode: String, name: String, role: String, branchId: String?) {
        connector.upsertUser.execute(
            uid = uid,
            employeeCode = employeeCode,
            name = name,
            role = role
        ) {
            this.branchId = branchId
        }
    }

    suspend fun upsertOutlet(
        id: String,
        code: String,
        name: String,
        lat: Double,
        lng: Double,
        address: String,
        priority: Int,
        status: String = "ACTIVE"
    ) {
        connector.upsertOutlet.execute(
            id = id,
            code = code,
            name = name,
            lat = lat,
            lng = lng,
            address = address,
            priority = priority,
            status = status
        )
    }

    suspend fun createShift(shift: ShiftEntity) {
        connector.createShift.execute(
            id = shift.id,
            uid = shift.collectorUid,
            collectorId = shift.collectorId,
            startedAt = Timestamp(Date(shift.startedAt)),
            status = shift.status
        )
    }

    suspend fun endShift(shiftId: String, endedAt: Long, lastLat: Double?, lastLng: Double?) {
        connector.endShift.execute(
            id = shiftId,
            endedAt = Timestamp(Date(endedAt))
        ) {
            this.lastLat = lastLat
            this.lastLng = lastLng
        }
    }

    suspend fun syncTelemetry(points: List<TelemetryPointEntity>) {
        if (points.isEmpty()) return
        points.forEach { point ->
            connector.insertTelemetry.execute(
                id = point.id,
                shiftId = point.shiftId,
                collectorUid = point.collectorUid,
                collectorId = point.collectorId,
                lat = point.lat,
                lng = point.lng,
                accuracyM = point.accuracyM.toDouble(),
                speedMps = point.speedMps.toDouble(),
                bearing = point.bearing.toDouble(),
                capturedAt = Timestamp(Date(point.capturedAt)),
                batteryPct = point.batteryPct,
                networkState = point.networkState,
                mockFlag = point.mockFlag
            )
        }
    }

    suspend fun syncVisits(visits: List<VisitEntity>) {
        if (visits.isEmpty()) return
        visits.forEach { visit ->
            connector.recordVisit.execute(
                id = visit.id,
                shiftId = visit.shiftId,
                userUid = visit.collectorUid,
                outletId = visit.outletId,
                arrivalAt = Timestamp(Date(visit.arrivalAt)),
                method = visit.method
            ) {
                this.departureAt = visit.departureAt?.let { Timestamp(Date(it)) }
                this.durationSec = visit.durationSec?.toInt()
                this.result = visit.result
                this.notes = visit.notes
            }
        }
    }

    suspend fun fetchActiveOutlets(): List<OutletEntity> {
        val response = connector.listActiveOutlets.execute()
        return response.data.outlets.map { outlet ->
            OutletEntity(
                id = outlet.id,
                code = outlet.code,
                name = outlet.name,
                lat = outlet.lat,
                lng = outlet.lng,
                address = outlet.address,
                priority = outlet.priority
            )
        }
    }
}
