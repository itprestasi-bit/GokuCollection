package com.collectionfield.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(shift: ShiftEntity)

    @Query("SELECT * FROM shifts WHERE collectorId = :collectorId AND status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(collectorId: String): Flow<ShiftEntity?>

    @Query("SELECT * FROM shifts WHERE collectorId = :collectorId AND status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActive(collectorId: String): ShiftEntity?

    @Query("SELECT * FROM shifts WHERE status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getAnyActive(): ShiftEntity?

    @Query("UPDATE shifts SET endedAt = :endedAt, status = 'ENDED', syncStatus = 'PENDING' WHERE id = :shiftId")
    suspend fun endShift(shiftId: String, endedAt: Long)

    @Query("UPDATE shifts SET firstLat = COALESCE(firstLat, :lat), firstLng = COALESCE(firstLng, :lng), lastLat = :lat, lastLng = :lng, syncStatus = 'PENDING' WHERE id = :shiftId")
    suspend fun updateLocation(shiftId: String, lat: Double, lng: Double)

    @Query("SELECT * FROM shifts WHERE collectorId = :collectorId ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(collectorId: String, limit: Int = 20): Flow<List<ShiftEntity>>

    @Query("SELECT * FROM shifts WHERE syncStatus IN ('PENDING','FAILED') ORDER BY startedAt ASC LIMIT :limit")
    suspend fun pending(limit: Int = 100): List<ShiftEntity>

    @Query("UPDATE shifts SET syncStatus = :status WHERE id IN (:ids)")
    suspend fun updateSyncStatus(ids: List<String>, status: String)
}

@Dao
interface TelemetryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: TelemetryPointEntity)

    @Query("SELECT COUNT(*) FROM telemetry_points WHERE collectorId = :collectorId AND syncStatus != 'SYNCED'")
    fun observePendingCount(collectorId: String): Flow<Int>

    @Query("SELECT * FROM telemetry_points WHERE collectorId = :collectorId ORDER BY capturedAt DESC LIMIT 1")
    fun observeLatest(collectorId: String): Flow<TelemetryPointEntity?>

    @Query("SELECT * FROM telemetry_points WHERE syncStatus IN ('PENDING','FAILED') ORDER BY capturedAt ASC LIMIT :limit")
    suspend fun pending(limit: Int = 250): List<TelemetryPointEntity>

    @Query("UPDATE telemetry_points SET syncStatus = :status, receivedAt = :receivedAt WHERE id IN (:ids)")
    suspend fun updateSyncStatus(ids: List<String>, status: String, receivedAt: Long?)
}

@Dao
interface OutletDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(outlets: List<OutletEntity>)

    /**
     * The full master list — ~7.7k rows. Only for screens that genuinely need
     * every outlet. Do NOT put this in a `combine` that re-emits on every GPS
     * fix: it rebuilds and re-diffs the whole list each time.
     */
    @Query("SELECT * FROM outlets WHERE status = 'ACTIVE' ORDER BY priority DESC, name ASC")
    fun observeActive(): Flow<List<OutletEntity>>

    /** The handful of outlets on today's route — the query the app should reach for. */
    @Query("SELECT * FROM outlets WHERE id IN (:ids)")
    fun observeByIds(ids: List<String>): Flow<List<OutletEntity>>

    @Query("SELECT * FROM outlets WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<OutletEntity>

    @Query("SELECT * FROM outlets WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<OutletEntity?>

    /** Removes outlets a delta sync reported as no longer active. */
    @Query("DELETE FROM outlets WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM outlets")
    suspend fun count(): Int
}

@Dao
interface VisitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(visit: VisitEntity)

    @Query("SELECT * FROM visits WHERE collectorId = :collectorId ORDER BY arrivalAt DESC LIMIT :limit")
    fun observeRecent(collectorId: String, limit: Int = 30): Flow<List<VisitEntity>>

    @Query("SELECT * FROM visits WHERE syncStatus IN ('PENDING','FAILED') ORDER BY arrivalAt ASC LIMIT :limit")
    suspend fun pending(limit: Int = 100): List<VisitEntity>

    @Query("UPDATE visits SET syncStatus = :status WHERE id IN (:ids)")
    suspend fun updateSyncStatus(ids: List<String>, status: String)

    @Query("SELECT * FROM visits WHERE shiftId = :shiftId AND outletId = :outletId AND departureAt IS NULL LIMIT 1")
    suspend fun getOpen(shiftId: String, outletId: String): VisitEntity?

    @Query("SELECT * FROM visits WHERE shiftId = :shiftId AND departureAt IS NULL LIMIT 1")
    suspend fun getAnyOpen(shiftId: String): VisitEntity?

    @Query("SELECT * FROM visits WHERE shiftId = :shiftId AND departureAt IS NULL LIMIT 1")
    fun observeAnyOpen(shiftId: String): Flow<VisitEntity?>

    @Query("SELECT * FROM visits WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VisitEntity?

    @Query("UPDATE visits SET departureAt = :departureAt, durationSec = :durationSec, syncStatus = 'PENDING' WHERE id = :id")
    suspend fun closeVisit(id: String, departureAt: Long, durationSec: Long)

    @Query("UPDATE visits SET notes = :notes, result = :result, photoUrl = :photoUrl, syncStatus = 'PENDING' WHERE id = :id")
    suspend fun updateResult(id: String, notes: String?, result: String?, photoUrl: String?)
}
