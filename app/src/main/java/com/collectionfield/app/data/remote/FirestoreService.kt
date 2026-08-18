package com.collectionfield.app.data.remote

import com.collectionfield.app.data.local.OutletEntity
import com.collectionfield.app.data.repository.OutletRepository
import com.collectionfield.app.domain.VisitOutlet
import com.collectionfield.app.domain.VisitPiutangItem
import com.collectionfield.app.domain.VisitPlan
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Joins today's assignment (stops from Firestore `assignments`) against the
 * locally cached outlet master data, so the daily-plan/route screens keep working
 * offline once outlets have been synced at least once.
 */
class FirestoreService(
    private val cloud: FirebaseCloudDataSource?,
    private val outletRepository: OutletRepository,
) {
    suspend fun fetchDailyPlan(collectorUid: String, date: String): Result<VisitPlan?> = runCatching {
        val remote = cloud ?: error("Firebase belum dikonfigurasi")
        val stops = remote.fetchTodayStops(collectorUid, date)
        if (stops.isEmpty()) return@runCatching null

        // Fetch only the stops' own outlets. This used to load the entire ~7.7k-row
        // master list into a map to look up a dozen ids.
        val localOutlets = outletRepository.getByIds(stops.map { it.outletId }).associateBy { it.id }
        val outlets = stops.mapIndexedNotNull { index, stop ->
            localOutlets[stop.outletId]?.let { outlet ->
                VisitOutlet(
                    outletId = outlet.id,
                    namaOutlet = outlet.name,
                    alamat = outlet.address,
                    latitude = outlet.lat,
                    longitude = outlet.lng,
                    piutangItems = resolvePiutangItems(outlet, stop.piutangTags),
                    urutanRute = index + 1,
                    status = "PENDING",
                )
            }
        }

        VisitPlan(id = "$collectorUid-$date", collectorId = collectorUid, tanggal = date, outlets = outlets)
    }

    /** Parses [OutletEntity.piutangJson] and keeps only the tags this stop was assigned to collect. */
    private fun resolvePiutangItems(outlet: OutletEntity, piutangTags: List<String>): List<VisitPiutangItem> {
        val json = outlet.piutangJson ?: return emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        return piutangTags.mapNotNull { tag ->
            val entry = obj.optJSONObject(tag) ?: return@mapNotNull null
            VisitPiutangItem(
                tag = tag,
                amount = entry.optDouble("amount", 0.0),
                jatuhTempo = entry.optString("dueDate", "").takeUnless { it.isEmpty() },
            )
        }
    }
}
