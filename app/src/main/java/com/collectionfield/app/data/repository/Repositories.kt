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
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

    suspend fun pending(uid: String, limit: Int = 100): List<ShiftEntity> = db.shiftDao().pending(uid, limit)
    suspend fun reclaimStuck(): Int = db.shiftDao().reclaimStuck()

    suspend fun updateSync(ids: List<String>, status: SyncStatus) {
        if (ids.isNotEmpty()) db.shiftDao().updateSyncStatus(ids, status.name)
    }
}

class TelemetryRepository(private val db: CollectionDatabase) {
    fun observePendingCount(collectorId: String): Flow<Int> = db.telemetryDao().observePendingCount(collectorId)
    fun observeLatest(collectorId: String): Flow<TelemetryPointEntity?> = db.telemetryDao().observeLatest(collectorId)
    suspend fun insert(point: TelemetryPointEntity) = db.telemetryDao().insert(point)
    suspend fun pending(uid: String, limit: Int = 250) = db.telemetryDao().pending(uid, limit)
    suspend fun reclaimStuck(): Int = db.telemetryDao().reclaimStuck()
    suspend fun pruneSynced(olderThanMs: Long): Int = db.telemetryDao().pruneSynced(olderThanMs)
    suspend fun updateSync(ids: List<String>, status: SyncStatus, receivedAt: Long? = null) {
        if (ids.isNotEmpty()) db.telemetryDao().updateSyncStatus(ids, status.name, receivedAt)
    }
}

class OutletRepository(
    context: Context,
    private val db: CollectionDatabase,
    private val cloud: FirebaseCloudDataSource?,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("outlet_sync", Context.MODE_PRIVATE)

    fun observeActive(): Flow<List<OutletEntity>> = db.outletDao().observeActive()

    /** Today's route only. Preferred over [observeActive] anywhere a screen or the
     *  geofence needs "the outlets that matter now" rather than the whole master list. */
    fun observeByIds(ids: List<String>): Flow<List<OutletEntity>> =
        if (ids.isEmpty()) flowOf(emptyList()) else db.outletDao().observeByIds(ids)

    suspend fun getByIds(ids: List<String>): List<OutletEntity> =
        if (ids.isEmpty()) emptyList() else db.outletDao().getByIds(ids)

    /**
     * Returns the named outlets, pulling any the phone has never seen.
     *
     * Bulk outlet sync is a throttled delta, so an outlet created this morning and
     * scheduled for today is not in the local cache yet. Callers that must resolve
     * specific ids — the daily plan above all — use this instead of getByIds, so a
     * freshly created outlet does not silently vanish from a collector's route.
     * Only the genuinely missing ids go to the network.
     */
    suspend fun ensureCached(ids: List<String>): List<OutletEntity> {
        if (ids.isEmpty()) return emptyList()
        val local = getByIds(ids)
        val missing = ids.toSet() - local.map { it.id }.toSet()
        if (missing.isEmpty()) return local

        val fetched = runCatching { cloud?.fetchOutletsByIds(missing.toList()).orEmpty() }
            .getOrElse {
                // Offline, or the outlet was deleted upstream. Better a partial route
                // than none — the caller shows what it could resolve.
                return local
            }
        if (fetched.isNotEmpty()) db.outletDao().upsertAll(fetched)
        return local + fetched
    }

    fun observeById(id: String?): Flow<OutletEntity?> =
        if (id == null) flowOf(null) else db.outletDao().observeById(id)

    /**
     * Syncs outlet master data into Room, pulling only what changed.
     *
     * Three modes, in order of preference:
     *  - **Delta** (normal): everything modified since the stored cursor. On a
     *    typical day that's a handful of documents.
     *  - **Full**: no cursor yet (first run on this device), or the periodic
     *    [FULL_RESYNC_INTERVAL_MS] safety net is due. The safety net exists
     *    because delta is only as trustworthy as the `updated_at` field — if any
     *    write path ever forgets to set it, that outlet would drift out of sync
     *    forever, and a monthly full pull bounds that damage.
     *  - **Skipped**: an unforced call inside [REFRESH_INTERVAL_MS].
     *
     * [force] bypasses the time throttle only. It deliberately does *not* force a
     * full pull — login used to do exactly that, and at 20 collectors those daily
     * logins alone were ~150k document reads against a 50k daily allowance.
     */
    suspend fun refreshFromCloud(force: Boolean = false): Result<Int> = runCatching {
        val remote = cloud ?: error("Firebase belum dikonfigurasi")

        val cachedCount = db.outletDao().count()
        val now = System.currentTimeMillis()
        val lastSyncAt = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
        if (!force && cachedCount > 0 && now - lastSyncAt < REFRESH_INTERVAL_MS) {
            return@runCatching cachedCount
        }

        val cursorSeconds = prefs.getLong(KEY_CURSOR_SECONDS, 0L)
        val lastFullAt = prefs.getLong(KEY_LAST_FULL_SYNC_AT, 0L)
        val needsFull =
            cachedCount == 0 || cursorSeconds == 0L || now - lastFullAt >= FULL_RESYNC_INTERVAL_MS

        // Read the cursor *before* fetching, so anything edited while this sync is
        // in flight is picked up next time instead of being skipped.
        val nextCursor = remote.serverNow()
        val since = if (needsFull) null else Timestamp(cursorSeconds, 0)

        val changed = remote.fetchActiveOutlets(since)

        val (active, inactive) = changed.partition { it.isActive }
        db.outletDao().upsertAll(active.map { it.entity })
        // A delta can carry outlets that were deactivated or archived; drop those
        // locally, otherwise the phone keeps offering a closed location.
        if (inactive.isNotEmpty()) {
            db.outletDao().deleteByIds(inactive.map { it.entity.id })
        }

        prefs.edit()
            .putLong(KEY_LAST_SYNC_AT, now)
            .putLong(KEY_CURSOR_SECONDS, nextCursor.seconds)
            .apply { if (needsFull) putLong(KEY_LAST_FULL_SYNC_AT, now) }
            .apply()

        if (needsFull) changed.size else db.outletDao().count()
    }

    private companion object {
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_FULL_SYNC_AT = "last_full_sync_at"
        const val KEY_CURSOR_SECONDS = "cursor_seconds"
        const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours
        const val FULL_RESYNC_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000L // 30 days
    }
}

class VisitRepository(private val db: CollectionDatabase) {
    fun observeRecent(collectorId: String): Flow<List<VisitEntity>> = db.visitDao().observeRecent(collectorId)
    suspend fun pending(uid: String, limit: Int = 100): List<VisitEntity> = db.visitDao().pending(uid, limit)
    suspend fun reclaimStuck(): Int = db.visitDao().reclaimStuck()
    suspend fun updateSync(ids: List<String>, status: SyncStatus) {
        if (ids.isNotEmpty()) db.visitDao().updateSyncStatus(ids, status.name)
    }

    suspend fun getOpen(shiftId: String, outletId: String): VisitEntity? = db.visitDao().getOpen(shiftId, outletId)
    suspend fun getAnyOpen(shiftId: String): VisitEntity? = db.visitDao().getAnyOpen(shiftId)
    fun observeAnyOpen(shiftId: String): Flow<VisitEntity?> = db.visitDao().observeAnyOpen(shiftId)
    fun observeVisitedOutletIds(shiftId: String): Flow<List<String>> = db.visitDao().observeVisitedOutletIds(shiftId)
    suspend fun visitedOutletIdsSince(collectorUid: String, sinceMs: Long): List<String> =
        db.visitDao().visitedOutletIdsSince(collectorUid, sinceMs)
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
    val outletRepository = OutletRepository(appContext, database, cloudDataSource)
    val visitRepository = VisitRepository(database)
    val firestoreService = FirestoreService(cloudDataSource, outletRepository, visitRepository)

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
