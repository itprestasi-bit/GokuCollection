package com.collectionfield.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.collectionfield.app.CollectionFieldApplication
import com.collectionfield.app.domain.SyncStatus
import java.util.concurrent.TimeUnit

/**
 * Offline-first sync path for Milestone 02.
 *
 * Room remains the source of truth on-device. When authenticated + online:
 * - shifts -> Cloud Firestore /shifts
 * - telemetry -> Cloud Firestore /shifts/{shiftId}/telemetry
 * - latest telemetry -> Realtime Database /live_locations/{collectorUid}
 * - visits -> Cloud Firestore /visits
 */
class TelemetrySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CollectionFieldApplication).container
        val cloud = container.cloudDataSource ?: return Result.success()
        val session = container.authRepository.currentSession() ?: return Result.success()

        // Anything still marked SYNCING belongs to a run that was killed before it
        // could finish — only one sync worker exists at a time, so nothing else can
        // legitimately own those rows. Left alone they are invisible to pending()
        // and permanently counted as unsynced, which is how a phone ends up
        // reporting "tertunda" forever with no way to clear it. Re-uploading is
        // harmless: every write is a set() on a deterministic document id.
        val reclaimed = container.telemetryRepository.reclaimStuck() +
            container.shiftRepository.reclaimStuck() +
            container.visitRepository.reclaimStuck()
        if (reclaimed > 0) {
            android.util.Log.i("TelemetrySync", "$reclaimed baris tersangkut dikembalikan ke antrean")
        }

        val shifts = container.shiftRepository.pending(session.uid)
        if (shifts.isNotEmpty()) {
            container.shiftRepository.updateSync(shifts.map { it.id }, SyncStatus.SYNCING)
            try {
                cloud.syncShifts(shifts.map { shift ->
                    if (shift.collectorUid.isBlank()) shift.copy(collectorUid = session.uid) else shift
                })
                container.shiftRepository.updateSync(shifts.map { it.id }, SyncStatus.SYNCED)
            } catch (_: Throwable) {
                container.shiftRepository.updateSync(shifts.map { it.id }, SyncStatus.FAILED)
                return Result.retry()
            }
        }

        while (true) {
            val points = container.telemetryRepository.pending(session.uid, 250)
            if (points.isEmpty()) break

            val ids = points.map { it.id }
            container.telemetryRepository.updateSync(ids, SyncStatus.SYNCING)
            try {
                val owned = points.map { point ->
                    if (point.collectorUid.isBlank()) point.copy(collectorUid = session.uid) else point
                }

                // Dense: every fix, encoded, one document per batch. This is what
                // the replay reads.
                owned.groupBy { it.shiftId }.forEach { (shiftId, chunk) ->
                    cloud.syncTrackChunk(shiftId, chunk)
                }

                // Thinned: one document per fix, but only every
                // TELEMETRY_SPACING_MS. The anti-fraud trigger and the offline
                // alerting are per-document consumers and neither gains anything
                // from a point every two seconds — they would just cost 15x the
                // writes and 15x the function invocations to learn the same thing.
                cloud.syncTelemetry(thinned(owned))

                container.telemetryRepository.updateSync(
                    ids = ids,
                    status = SyncStatus.SYNCED,
                    receivedAt = System.currentTimeMillis(),
                )
            } catch (_: Throwable) {
                container.telemetryRepository.updateSync(ids, SyncStatus.FAILED)
                return Result.retry()
            }
        }

        val visits = container.visitRepository.pending(session.uid)
        if (visits.isNotEmpty()) {
            val ids = visits.map { it.id }
            container.visitRepository.updateSync(ids, SyncStatus.SYNCING)
            try {
                cloud.syncVisits(visits.map { visit ->
                    if (visit.collectorUid.isBlank()) visit.copy(collectorUid = session.uid) else visit
                })
                container.visitRepository.updateSync(ids, SyncStatus.SYNCED)
            } catch (_: Throwable) {
                container.visitRepository.updateSync(ids, SyncStatus.FAILED)
                return Result.retry()
            }
        }

        container.telemetryRepository.pruneSynced(
            System.currentTimeMillis() - LOCAL_RETENTION_MS,
        )

        return Result.success()
    }

    /** Keeps the first fix of each [TELEMETRY_SPACING_MS] window, per shift. */
    private fun thinned(points: List<com.collectionfield.app.data.local.TelemetryPointEntity>) =
        points.sortedBy { it.capturedAt }
            .groupBy { it.shiftId }
            .flatMap { (_, ofShift) ->
                var lastKept = Long.MIN_VALUE
                ofShift.filter { point ->
                    if (point.capturedAt - lastKept >= TELEMETRY_SPACING_MS) {
                        lastKept = point.capturedAt
                        true
                    } else {
                        false
                    }
                }
            }

    companion object {
        private const val UNIQUE_WORK = "firebase-field-sync"
        /** Spacing of the per-document telemetry copy. See the note at the call site. */
        private const val TELEMETRY_SPACING_MS = 30_000L
        /** How long uploaded trail is kept on the phone before being pruned. */
        private const val LOCAL_RETENTION_MS = 14L * 24 * 60 * 60 * 1000

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<TelemetrySyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
