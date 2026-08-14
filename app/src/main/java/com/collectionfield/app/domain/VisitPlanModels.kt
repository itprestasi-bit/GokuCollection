package com.collectionfield.app.domain

import com.google.firebase.firestore.PropertyName

data class VisitPlan(
    val id: String = "",
    @get:PropertyName("collector_id") @set:PropertyName("collector_id") var collectorId: String = "",
    val tanggal: String = "", // YYYY-MM-DD
    val outlets: List<VisitOutlet> = emptyList()
)

/** One receivable to collect at a stop — an outlet can have several, one per tag. */
data class VisitPiutangItem(
    val tag: String,
    val amount: Double,
    val jatuhTempo: String?, // YYYY-MM-DD
)

data class VisitOutlet(
    @get:PropertyName("outlet_id") @set:PropertyName("outlet_id") var outletId: String = "",
    @get:PropertyName("nama_outlet") @set:PropertyName("nama_outlet") var namaOutlet: String = "",
    val alamat: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val piutangItems: List<VisitPiutangItem> = emptyList(), // only the tags admin picked for this visit
    @get:PropertyName("urutan_rute") @set:PropertyName("urutan_rute") var urutanRute: Int = 0,
    val status: String = "PENDING" // PENDING, SELESAI
)

/** A stop from `assignments/{id}.stops` — which outlet, and which of its receivables to collect. */
data class AssignmentStopRef(
    val outletId: String,
    val piutangTags: List<String>,
)
