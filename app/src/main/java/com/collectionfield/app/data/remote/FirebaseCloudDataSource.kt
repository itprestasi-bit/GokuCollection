package com.collectionfield.app.data.remote

import com.collectionfield.app.data.local.OutletEntity
import com.collectionfield.app.data.local.ShiftEntity
import com.collectionfield.app.data.local.TelemetryPointEntity
import com.collectionfield.app.util.PolylineCodec
import com.collectionfield.app.data.local.VisitEntity
import com.collectionfield.app.domain.AssignmentStopRef
import com.google.firebase.Timestamp
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject
import java.util.Date

/**
 * Single source of truth for cloud sync: Cloud Firestore for durable records
 * (shifts, telemetry trail, visits, outlets, users), Realtime Database only
 * for the ephemeral live_locations feed the dashboard's map subscribes to.
 */
/** A road route returned by the Routes API, already decoded. */
data class PlannedRoute(
    val points: List<Pair<Double, Double>>,
    val distanceMeters: Double,
    val durationSeconds: Long,
    /** Order the server put the intermediate stops in, empty if it did not reorder. */
    val order: List<Int>,
)

class FirebaseCloudDataSource {
    private val firestore: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val realtime: FirebaseDatabase get() = FirebaseDatabase.getInstance()

    // Functions live in asia-southeast2 (Eventarc pins Firestore triggers to the
    // database's region), so the default us-central1 lookup would 404 every call.
    private val functions: FirebaseFunctions get() = FirebaseFunctions.getInstance("asia-southeast2")

    suspend fun syncShifts(shifts: List<ShiftEntity>) {
        if (shifts.isEmpty()) return

        shifts.forEach { shift ->
            val ref = firestore.collection("shifts").document(shift.id)
            val data = mutableMapOf<String, Any?>(
                "collector_id" to shift.collectorId,
                "collector_uid" to shift.collectorUid,
                "status" to if (shift.status == "ACTIVE") "active" else "ended",
            )
            if (shift.status == "ACTIVE") {
                data["start_time"] = Timestamp(Date(shift.startedAt))
                if (shift.firstLat != null && shift.firstLng != null) {
                    data["start_location"] = mapOf("lat" to shift.firstLat, "lng" to shift.firstLng)
                }
            } else {
                data["end_time"] = shift.endedAt?.let { Timestamp(Date(it)) }
                if (shift.lastLat != null && shift.lastLng != null) {
                    data["end_location"] = mapOf("lat" to shift.lastLat, "lng" to shift.lastLng)
                }
            }
            ref.set(data, SetOptions.merge()).awaitResult()
        }

        // Fallback in case the immediate write from LocationTrackingService.stopTracking()
        // didn't make it out (e.g. no network at that exact moment) — batched sync
        // catches up once connectivity returns.
        shifts.filter { it.status == "ENDED" && it.collectorUid.isNotBlank() }.forEach { shift ->
            realtime.reference.child("live_locations").child(shift.collectorUid)
                .updateChildren(
                    mapOf(
                        "status" to "offline",
                        "shift_id" to shift.id,
                        "updated_at" to ServerValue.TIMESTAMP,
                    ),
                )
                .awaitResult()
        }
    }

    suspend fun syncTelemetry(points: List<TelemetryPointEntity>) {
        if (points.isEmpty()) return

        points.chunked(250).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { point ->
                val ref = firestore.collection("shifts").document(point.shiftId)
                    .collection("telemetry").document(point.id)
                batch.set(
                    ref,
                    mapOf(
                        "collector_uid" to point.collectorUid,
                        "collector_id" to point.collectorId,
                        "lat" to point.lat,
                        "lng" to point.lng,
                        "accuracy_m" to point.accuracyM,
                        "speed_mps" to point.speedMps,
                        "bearing" to point.bearing,
                        "captured_at" to Timestamp(Date(point.capturedAt)),
                        "battery_pct" to point.batteryPct,
                        "network_state" to point.networkState,
                        "mock_flag" to point.mockFlag,
                        "low_confidence" to (point.accuracyM < 0f || point.accuracyM > 50f),
                    ),
                )
            }
            batch.commit().awaitResult()
        }
    }

    /**
     * Uploads a stretch of trail as a single encoded document.
     *
     * The same fixes are also written individually by [syncTelemetry], but thinned
     * out — those exist for the anti-fraud trigger and the offline alerting, which
     * need a document per event and do not need every point. This is the opposite
     * case: the replay wants every point and does not want 14,400 documents. So the
     * trail goes out twice, in the shape each consumer actually needs, and the dense
     * copy costs one write per batch instead of one per fix.
     *
     * The id is the first fix's timestamp, which makes a re-send idempotent for the
     * same reason the telemetry ids are: a retry after a lost acknowledgement
     * rewrites the same document rather than adding a duplicate.
     */
    suspend fun syncTrackChunk(shiftId: String, points: List<TelemetryPointEntity>) {
        if (points.isEmpty()) return
        val sorted = points.sortedBy { it.capturedAt }
        val t0 = sorted.first().capturedAt

        firestore.collection("shifts").document(shiftId)
            .collection("track").document(t0.toString())
            .set(
                mapOf(
                    "collector_uid" to sorted.first().collectorUid,
                    "collector_id" to sorted.first().collectorId,
                    "started_at" to Timestamp(Date(t0)),
                    "ended_at" to Timestamp(Date(sorted.last().capturedAt)),
                    "point_count" to sorted.size,
                    "path" to PolylineCodec.encodePath(sorted.map { it.lat to it.lng }),
                    // Seconds since started_at, so the replay keeps real timing
                    // instead of assuming an even cadence across dropped fixes.
                    "time_offsets" to PolylineCodec.encodeValues(
                        sorted.map { (it.capturedAt - t0) / 1000 },
                    ),
                    "speeds" to PolylineCodec.encodeValues(
                        PolylineCodec.speedsToDeci(sorted.map { it.speedMps }),
                    ),
                ),
            )
            .awaitResult()
    }

    /**
     * The driving route through today's stops, as road geometry.
     *
     * Straight lines between stops show the order but not the journey — and the
     * distance they imply is not the distance the collector will ride. This asks
     * the server, which holds the Routes API key; the key is billable and has no
     * business being on a device that leaves the office.
     *
     * Returns null on any failure. The screen keeps its straight-line fallback,
     * because a rough route beats an empty map, and a rider who can see the order
     * of their stops can still work.
     */
    suspend fun planRoute(
        origin: Pair<Double, Double>,
        stops: List<Pair<Double, Double>>,
    ): PlannedRoute? = runCatching {
        val payload = mapOf(
            "origin" to mapOf("lat" to origin.first, "lng" to origin.second),
            "stops" to stops.map { mapOf("lat" to it.first, "lng" to it.second) },
        )
        val result = functions.getHttpsCallable("planRoute").call(payload).awaitResult()

        @Suppress("UNCHECKED_CAST")
        val data = result.data as? Map<String, Any?> ?: return@runCatching null
        val encoded = data["polyline"] as? String ?: return@runCatching null
        PlannedRoute(
            points = PolylineCodec.decodePath(encoded),
            distanceMeters = (data["distanceMeters"] as? Number)?.toDouble() ?: 0.0,
            durationSeconds = (data["durationSeconds"] as? Number)?.toLong() ?: 0L,
            order = (data["order"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty(),
        )
    }.getOrNull()

    suspend fun syncVisits(visits: List<VisitEntity>) {
        if (visits.isEmpty()) return
        visits.forEach { visit ->
            val ref = firestore.collection("visits").document(visit.id)
            val data = mutableMapOf<String, Any?>(
                "collector_id" to visit.collectorId,
                "collector_uid" to visit.collectorUid,
                "shift_id" to visit.shiftId,
                "outlet_id" to visit.outletId,
                "arrival_at" to Timestamp(Date(visit.arrivalAt)),
                "method" to visit.method,
            )
            visit.arrivalLat?.let { data["arrival_lat"] = it }
            visit.arrivalLng?.let { data["arrival_lng"] = it }
            visit.departureAt?.let { data["departure_at"] = Timestamp(Date(it)) }
            visit.durationSec?.let { data["duration_sec"] = it }
            visit.result?.let { data["result"] = it }
            visit.notes?.let { data["notes"] = it }
            visit.photoUrl?.let { data["photo_urls"] = FieldValue.arrayUnion(it) }
            ref.set(data, SetOptions.merge()).awaitResult()
        }
    }

    suspend fun upsertUser(uid: String, employeeCode: String, name: String, role: String, branchId: String?, deviceId: String?) {
        val data = mapOf(
            "employee_code" to employeeCode,
            "name" to name,
            "role" to role.lowercase(),
            "branch_id" to branchId,
            "device_id" to deviceId,
            "updated_at" to FieldValue.serverTimestamp(),
        )
        firestore.collection("users").document(uid).set(data, SetOptions.merge()).awaitResult()
    }

    /** One outlet as it arrived from Firestore, plus whether it is still active. */
    data class RemoteOutlet(val entity: OutletEntity, val isActive: Boolean)

    /**
     * Outlet master data.
     *
     * [since] null pulls every active outlet — the first sync on a device, which
     * has to be complete. Otherwise it pulls only what changed since that instant.
     *
     * The delta query deliberately does **not** filter on status. Filtering would
     * make a deactivated outlet simply stop matching, so the phone would keep a
     * stale copy and keep offering check-in at a location head office has closed.
     * Fetching everything that changed and letting the caller drop the non-active
     * ones is what makes deactivation actually propagate.
     *
     * Cost: at ~7.7k outlets a full pull is 7,683 document reads. With 20
     * collectors each logging in daily that was 150k+ reads/day against a 50k
     * daily allowance. A delta pull on a normal day is a handful.
     */
    suspend fun fetchActiveOutlets(since: Timestamp? = null): List<RemoteOutlet> {
        val query = if (since == null) {
            firestore.collection("outlets").whereEqualTo("status", "active")
        } else {
            firestore.collection("outlets").whereGreaterThan("updated_at", since)
        }

        val snapshot = query.get().awaitResult()
        return snapshot.documents.map { doc ->
            val status = doc.getString("status").orEmpty()
            RemoteOutlet(
                entity = toOutletEntity(doc),
                // `since == null` already filtered to active in the query itself.
                isActive = since == null || status.equals("active", ignoreCase = true),
            )
        }
    }

    /**
     * Fetches named outlets regardless of when they last changed.
     *
     * The bulk sync is a delta on `updated_at` behind a six-hour throttle, which is
     * right for 7.7k rows of slow-moving master data but leaves a gap: an outlet
     * created this morning and put on today's schedule is not on the phone yet. The
     * daily plan then quietly drops that stop, and the collector sees an empty
     * route with nothing to explain it. Resolving the handful of ids a schedule
     * actually names costs a few document reads and closes that gap.
     */
    suspend fun fetchOutletsByIds(ids: List<String>): List<OutletEntity> {
        if (ids.isEmpty()) return emptyList()
        // Firestore caps an `in` filter at 30 values per query.
        return ids.distinct().chunked(30).flatMap { chunk ->
            firestore.collection("outlets")
                .whereIn(FieldPath.documentId(), chunk)
                .get().awaitResult()
                .documents.map { toOutletEntity(it) }
        }
    }

    private fun toOutletEntity(doc: DocumentSnapshot): OutletEntity {
        @Suppress("UNCHECKED_CAST")
        val piutangMap = doc.get("piutang") as? Map<String, Map<String, Any?>>
        return OutletEntity(
            id = doc.id,
            code = doc.getString("code").orEmpty(),
            name = doc.getString("name").orEmpty(),
            lat = doc.getDouble("lat") ?: 0.0,
            lng = doc.getDouble("lng") ?: 0.0,
            address = doc.getString("address").orEmpty(),
            radiusM = (doc.getLong("radius_m") ?: 30L).toInt(),
            priority = (doc.getLong("priority") ?: 1L).toInt(),
            status = "ACTIVE",
            piutangJson = piutangMapToJson(piutangMap),
        )
    }

    /**
     * Server time, used as the delta cursor.
     *
     * Taken from the server rather than the phone's clock on purpose: a device
     * whose clock runs fast would store a future cursor and then silently skip
     * every edit made in between. Costs one document write + read.
     */
    suspend fun serverNow(): Timestamp {
        val ref = firestore.collection("_sync").document("clock")
        ref.set(mapOf("at" to FieldValue.serverTimestamp()), SetOptions.merge()).awaitResult()
        return ref.get().awaitResult().getTimestamp("at") ?: Timestamp.now()
    }

    private fun piutangMapToJson(piutangMap: Map<String, Map<String, Any?>>?): String? {
        if (piutangMap.isNullOrEmpty()) return null
        val obj = JSONObject()
        for ((tag, entry) in piutangMap) {
            val entryObj = JSONObject()
            entryObj.put("amount", (entry["amount"] as? Number)?.toDouble() ?: 0.0)
            entryObj.put("dueDate", entry["due_date"] as? String)
            obj.put(tag, entryObj)
        }
        return obj.toString()
    }

    /** Immediate, unbatched write for the live-tracking marker — called on every GPS fix. */
    suspend fun writeLiveLocation(uid: String, fields: Map<String, Any?>) {
        realtime.reference.child("live_locations").child(uid).setValue(fields).awaitResult()
    }

    /**
     * Partial update of just the status field — unlike [writeLiveLocation] this uses
     * updateChildren (merge), not setValue, so lat/lng/collector_name/etc already on
     * the node are preserved. Used for immediate shift start/end status changes,
     * where we want the collector to keep showing at their last known position.
     */
    /**
     * Heading-only patch.
     *
     * Exists because a parked collector barely sends position updates — the
     * telemetry gate deliberately goes quiet — so a heading riding along with
     * position would not reach the map for minutes. This is `updateChildren`, so
     * it touches two keys instead of rewriting the node, and it is rate-limited
     * by the caller to genuine turns rather than sensor noise.
     */
    suspend fun updateLiveLocationHeading(uid: String, heading: Float) {
        realtime.reference.child("live_locations").child(uid)
            .updateChildren(mapOf("heading" to heading, "updated_at" to ServerValue.TIMESTAMP))
            .awaitResult()
    }

    suspend fun updateLiveLocationStatus(uid: String, status: String) {
        realtime.reference.child("live_locations").child(uid)
            .updateChildren(mapOf("status" to status, "updated_at" to ServerValue.TIMESTAMP))
            .awaitResult()
    }

    // Doc id is deterministic ({collectorUid}_{date}) so this is a direct `get`,
    // not a `list` query — Firestore rules can only prove the owner-only read
    // check (resource.data.collector_uid == auth.uid) for a `get`, not for a
    // filtered list query. See firestore.rules `assignments` for the split rule.
    suspend fun fetchTodayStops(collectorUid: String, date: String): List<AssignmentStopRef> {
        val doc = firestore.collection("assignments").document("${collectorUid}_$date").get().awaitResult()
        if (!doc.exists()) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val rawStops = doc.get("stops") as? List<Map<String, Any?>> ?: return emptyList()
        return rawStops.mapNotNull { s ->
            val outletId = s["outlet_id"] as? String ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val tags = (s["piutang_tags"] as? List<String>).orEmpty()
            AssignmentStopRef(outletId, tags)
        }
    }

    /**
     * Updates just the [tag] receivable's amount via Firestore dot-notation
     * (`piutang.MCO.amount`) — [update] (not [set]/merge) is required for a dotted
     * key to be treated as a nested field path rather than a literal field name,
     * so this only touches the one tag, leaving an outlet's other receivables intact.
     */
    suspend fun updateOutletDebt(outletId: String, tag: String, status: String, remainingDebt: Double?) {
        val updates = mutableMapOf<String, Any>(
            "status_bayar" to status,
            "updated_at" to FieldValue.serverTimestamp(),
        )
        if (tag.isNotBlank()) {
            remainingDebt?.let { updates["piutang.$tag.amount"] = it }
        }
        firestore.collection("outlets").document(outletId).update(updates).awaitResult()
    }

}
