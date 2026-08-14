package com.collectionfield.app.data.repository

import android.content.Context
import com.collectionfield.app.data.local.CollectionDatabase
import com.collectionfield.app.data.local.OutletEntity
import com.collectionfield.app.data.local.ShiftEntity
import com.collectionfield.app.data.local.TelemetryPointEntity
import com.collectionfield.app.data.local.VisitEntity
import com.collectionfield.app.data.remote.CloudStorageService
import com.collectionfield.app.data.remote.FirebaseAuthRepository
import com.collectionfield.app.data.remote.FirebaseBootstrap
import com.collectionfield.app.data.remote.FirebaseCloudDataSource
import com.collectionfield.app.data.remote.FirestoreService
import com.collectionfield.app.domain.CollectorSession
import com.collectionfield.app.domain.ShiftStatus
import com.collectionfield.app.domain.SyncStatus
import com.collectionfield.app.domain.UserRole
import com.collectionfield.app.sync.TelemetrySyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

class SessionRepository(context: Context) {
    private val prefs = context.getSharedPreferences("collection_session", Context.MODE_PRIVATE)

    fun currentCollectorId(): String? = currentSession()?.employeeCode
    fun currentCollectorUid(): String? = currentSession()?.uid

    fun currentSession(): CollectorSession? {
        val uid = prefs.getString(KEY_UID, null) ?: return null
        val employeeCode = prefs.getString(KEY_COLLECTOR_ID, null) ?: return null
        val displayName = prefs.getString(KEY_DISPLAY_NAME, null) ?: employeeCode
        val role = runCatching {
            UserRole.valueOf(prefs.getString(KEY_ROLE, UserRole.COLLECTOR.name).orEmpty())
        }.getOrDefault(UserRole.COLLECTOR)
        return CollectorSession(
            uid = uid,
            employeeCode = employeeCode,
            displayName = displayName,
            role = role,
            branchId = prefs.getString(KEY_BRANCH_ID, null),
            teamId = prefs.getString(KEY_TEAM_ID, null),
        )
    }

    fun save(session: CollectorSession) {
        prefs.edit()
            .putString(KEY_UID, session.uid)
            .putString(KEY_COLLECTOR_ID, session.employeeCode)
            .putString(KEY_DISPLAY_NAME, session.displayName)
            .putString(KEY_ROLE, session.role.name)
            .putString(KEY_BRANCH_ID, session.branchId)
            .putString(KEY_TEAM_ID, session.teamId)
            .apply()
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_UID = "collector_uid"
        private const val KEY_COLLECTOR_ID = "collector_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ROLE = "role"
        private const val KEY_BRANCH_ID = "branch_id"
        private const val KEY_TEAM_ID = "team_id"
    }
}

class ShiftRepository(
    private val context: Context,
    private val db: CollectionDatabase,
) {
    fun observeActive(collectorId: String): Flow<ShiftEntity?> = db.shiftDao().observeActive(collectorId)
    fun observeRecent(collectorId: String): Flow<List<ShiftEntity>> = db.shiftDao().observeRecent(collectorId)

    suspend fun getActive(collectorId: String): ShiftEntity? = db.shiftDao().getActive(collectorId)
    suspend fun getAnyActive(): ShiftEntity? = db.shiftDao().getAnyActive()

    suspend fun startShift(collectorId: String, collectorUid: String): ShiftEntity {
        db.shiftDao().getActive(collectorId)?.let { return it }
        val shift = ShiftEntity(
            id = UUID.randomUUID().toString(),
            collectorId = collectorId,
            collectorUid = collectorUid,
            startedAt = System.currentTimeMillis(),
            status = ShiftStatus.ACTIVE.name,
            syncStatus = SyncStatus.PENDING.name,
        )
        db.shiftDao().upsert(shift)
        TelemetrySyncWorker.enqueue(context)
        return shift
    }

    suspend fun endShift(shiftId: String) {
        db.shiftDao().endShift(shiftId, System.currentTimeMillis())
        TelemetrySyncWorker.enqueue(context)
    }

    suspend fun updateLocation(shiftId: String, lat: Double, lng: Double) {
        db.shiftDao().updateLocation(shiftId, lat, lng)
    }

    suspend fun pending(limit: Int = 100): List<ShiftEntity> = db.shiftDao().pending(limit)

    suspend fun updateSync(ids: List<String>, status: SyncStatus) {
        if (ids.isNotEmpty()) db.shiftDao().updateSyncStatus(ids, status.name)
    }
}

class TelemetryRepository(private val db: CollectionDatabase) {
    fun observePendingCount(collectorId: String): Flow<Int> = db.telemetryDao().observePendingCount(collectorId)
    fun observeLatest(collectorId: String): Flow<TelemetryPointEntity?> = db.telemetryDao().observeLatest(collectorId)
    suspend fun insert(point: TelemetryPointEntity) = db.telemetryDao().insert(point)
    suspend fun pending(limit: Int = 250) = db.telemetryDao().pending(limit)
    suspend fun updateSync(ids: List<String>, status: SyncStatus, receivedAt: Long? = null) {
        if (ids.isNotEmpty()) db.telemetryDao().updateSyncStatus(ids, status.name, receivedAt)
    }
}

class OutletRepository(
    private val db: CollectionDatabase,
    private val cloud: FirebaseCloudDataSource?,
) {
    fun observeActive(): Flow<List<OutletEntity>> = db.outletDao().observeActive()

    suspend fun refreshFromCloud(): Result<Int> = runCatching {
        val remote = cloud ?: error("Firebase belum dikonfigurasi")
        val outlets = remote.fetchActiveOutlets()
        db.outletDao().upsertAll(outlets)
        outlets.size
    }
}

class VisitRepository(private val db: CollectionDatabase) {
    fun observeRecent(collectorId: String): Flow<List<VisitEntity>> = db.visitDao().observeRecent(collectorId)
    suspend fun pending(limit: Int = 100): List<VisitEntity> = db.visitDao().pending(limit)
    suspend fun updateSync(ids: List<String>, status: SyncStatus) {
        if (ids.isNotEmpty()) db.visitDao().updateSyncStatus(ids, status.name)
    }

    suspend fun getOpen(shiftId: String, outletId: String): VisitEntity? = db.visitDao().getOpen(shiftId, outletId)
    suspend fun getAnyOpen(shiftId: String): VisitEntity? = db.visitDao().getAnyOpen(shiftId)
    fun observeAnyOpen(shiftId: String): Flow<VisitEntity?> = db.visitDao().observeAnyOpen(shiftId)
    suspend fun getById(id: String): VisitEntity? = db.visitDao().getById(id)

    suspend fun openVisit(
        id: String,
        shiftId: String,
        collectorId: String,
        collectorUid: String,
        outletId: String,
        arrivalAt: Long,
        method: String,
        arrivalLat: Double? = null,
        arrivalLng: Double? = null,
    ) {
        db.visitDao().upsert(
            VisitEntity(
                id = id,
                shiftId = shiftId,
                collectorId = collectorId,
                collectorUid = collectorUid,
                outletId = outletId,
                arrivalAt = arrivalAt,
                arrivalLat = arrivalLat,
                arrivalLng = arrivalLng,
                method = method,
                syncStatus = SyncStatus.PENDING.name,
            ),
        )
    }

    suspend fun closeVisit(id: String, departureAt: Long, durationSec: Long) {
        db.visitDao().closeVisit(id, departureAt, durationSec)
    }

    suspend fun updateResult(id: String, notes: String?, result: String?, photoUrl: String?) {
        db.visitDao().updateResult(id, notes, result, photoUrl)
    }
}

class AppContainer(context: Context) {
    val appContext = context.applicationContext
    val firebaseReady: Boolean = FirebaseBootstrap.initialize(appContext)
    val database: CollectionDatabase = CollectionDatabase.get(appContext)
    val sessionRepository = SessionRepository(appContext)
    val themePreferences = ThemePreferences(appContext)
    val storageService = CloudStorageService()
    val cloudDataSource: FirebaseCloudDataSource? = if (firebaseReady) FirebaseCloudDataSource() else null
    val authRepository = FirebaseAuthRepository(appContext, sessionRepository, cloudDataSource)
    val shiftRepository = ShiftRepository(appContext, database)
    val telemetryRepository = TelemetryRepository(database)
    val outletRepository = OutletRepository(database, cloudDataSource)
    val visitRepository = VisitRepository(database)
    val firestoreService = FirestoreService(cloudDataSource, outletRepository)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueueSync() {
        if (firebaseReady && authRepository.currentSession() != null) {
            TelemetrySyncWorker.enqueue(appContext)
        }
    }

    /** Local-only fallback seed so the outlet list isn't empty before the first cloud refresh. */
    fun seedDemoDataIfNeeded() {
        appScope.launch {
            if (database.outletDao().count() == 0) {
                database.outletDao().upsertAll(
                    listOf(
                        OutletEntity("OUT-001", "OUT001", "Toko Makmur", -6.21462, 106.84513, "Jakarta Pusat", priority = 3),
                        OutletEntity("OUT-002", "OUT002", "Toko Sejahtera", -6.20876, 106.82011, "Tanah Abang, Jakarta", priority = 2),
                        OutletEntity("OUT-003", "OUT003", "CV Sumber Rejeki", -6.22510, 106.83058, "Karet, Jakarta", priority = 2),
                        OutletEntity("OUT-004", "OUT004", "Toko Maju Jaya", -6.19671, 106.82310, "Menteng, Jakarta", priority = 1),
                        OutletEntity("OUT-005", "OUT005", "UD Berkah", -6.23855, 106.82624, "Setiabudi, Jakarta", priority = 1),
                    ),
                )
            }
        }
    }
}
