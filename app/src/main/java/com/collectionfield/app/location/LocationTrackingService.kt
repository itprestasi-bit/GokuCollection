package com.collectionfield.app.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import com.collectionfield.app.CollectionFieldApplication
import com.collectionfield.app.MainActivity
import com.collectionfield.app.R
import com.collectionfield.app.data.local.TelemetryPointEntity
import com.collectionfield.app.domain.SyncStatus
import com.collectionfield.app.domain.TrackingMode
import com.collectionfield.app.domain.TrackingPolicy
import com.collectionfield.app.util.CompassProvider
import com.collectionfield.app.util.DeviceState
import com.collectionfield.app.util.HeadingFilter
import com.collectionfield.app.util.LocationRefiner
import com.collectionfield.app.util.MovementClassifier
import com.collectionfield.app.util.StationaryDetector
import com.collectionfield.app.util.TelemetryGate
import com.collectionfield.app.sync.TelemetrySyncWorker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LocationTrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var notificationManager: NotificationManager
    private val classifier = MovementClassifier()
    private val refiner = LocationRefiner()
    private val gate = TelemetryGate()
    private val stationaryDetector = StationaryDetector()

    /**
     * Compass heading, kept separate from the GPS bearing.
     *
     * They answer different questions: GPS bearing is course over ground (only
     * meaningful while moving), the compass is which way the handset is pointing
     * (meaningful standing still). The dashboard prefers this and falls back to
     * bearing, so the icon keeps a direction even when the collector is parked.
     *
     * Reaches the dashboard two ways: it rides along with every position update,
     * and — for a collector who is parked and therefore barely sending positions
     * — a real turn also pushes a heading-only patch. See [onHeading].
     */
    @Volatile
    private var latestHeading: Float? = null
    private var lastPushedHeading: Float? = null
    private var lastHeadingPushAtMs = 0L
    private val compass by lazy {
        CompassProvider(applicationContext) { heading -> onHeading(heading) }
    }

    /**
     * Compass callback. Always updates the local value; pushes to the live feed
     * only on a genuine turn.
     *
     * The filter also emits periodically while the heading is steady, which is
     * useful locally but must not become network traffic — otherwise a phone
     * sitting on a table would write to the database every ten seconds forever.
     * So the push is gated a second time here, on real rotation.
     */
    private fun onHeading(heading: Float) {
        latestHeading = heading
        val uid = currentCollectorUid ?: return

        val now = System.currentTimeMillis()
        val previous = lastPushedHeading
        val turned = previous == null ||
            kotlin.math.abs(HeadingFilter.shortestDelta(previous, heading)) >= HEADING_PUSH_DEGREES
        if (!turned || now - lastHeadingPushAtMs < HEADING_PUSH_MIN_INTERVAL_MS) return

        lastPushedHeading = heading
        lastHeadingPushAtMs = now
        serviceScope.launch {
            runCatching { container.cloudDataSource?.updateLiveLocationHeading(uid, heading) }
        }
    }

    private var currentShiftId: String? = null
    private var currentCollectorId: String? = null
    private var currentCollectorUid: String? = null
    private var currentCollectorName: String? = null
    private var appliedMode: TrackingMode? = null

    private val container by lazy { (application as CollectionFieldApplication).container }
    private val statePrefs by lazy { getSharedPreferences("tracking_service", Context.MODE_PRIVATE) }
    private val geofenceManager by lazy { GeofenceVisitManager(container.visitRepository) }
    private var planWatcher: Job? = null
    private var assignedOutletCount = 0

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::handleLocation)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            ACTION_START -> {
                val shiftId = intent.getStringExtra(EXTRA_SHIFT_ID)
                val collectorId = intent.getStringExtra(EXTRA_COLLECTOR_ID)
                val collectorUid = intent.getStringExtra(EXTRA_COLLECTOR_UID)
                if (shiftId != null && collectorId != null && collectorUid != null) startTracking(shiftId, collectorId, collectorUid)
            }
            else -> restoreTrackingIfPossible()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(locationCallback)
        compass.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun restoreTrackingIfPossible() {
        val shiftId = statePrefs.getString(EXTRA_SHIFT_ID, null)
        val collectorId = statePrefs.getString(EXTRA_COLLECTOR_ID, null)
        val collectorUid = statePrefs.getString(EXTRA_COLLECTOR_UID, null)
        if (shiftId != null && collectorId != null && collectorUid != null) startTracking(shiftId, collectorId, collectorUid) else stopSelf()
    }

    private fun startTracking(shiftId: String, collectorId: String, collectorUid: String) {
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        currentShiftId = shiftId
        currentCollectorId = collectorId
        currentCollectorUid = collectorUid
        currentCollectorName = container.sessionRepository.currentSession()?.displayName
        statePrefs.edit()
            .putString(EXTRA_SHIFT_ID, shiftId)
            .putString(EXTRA_COLLECTOR_ID, collectorId)
            .putString(EXTRA_COLLECTOR_UID, collectorUid)
            .apply()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Tracking aktif • menunggu GPS"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        applyTrackingMode(TrackingMode.MOVING, force = true)
        startPlanWatcher(collectorUid)
        compass.start()

        // Immediate optimistic status so the collector shows as "moving" on the
        // admin map right away, instead of waiting for the first GPS fix (or
        // lingering on whatever status was left over from their last shift).
        serviceScope.launch {
            runCatching { container.cloudDataSource?.updateLiveLocationStatus(collectorUid, "moving") }
        }
    }

    /**
     * Keeps the geofence set in sync with today's assignment.
     *
     * Re-read periodically rather than once, so a stop the office adds or removes
     * mid-shift starts (or stops) counting for check-in without the collector
     * having to restart anything. It's one document `get()` per pass — the
     * assignment doc id is deterministic — so the cost is negligible.
     */
    private fun startPlanWatcher(collectorUid: String) {
        planWatcher?.cancel()
        planWatcher = serviceScope.launch {
            while (true) {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val outlets = runCatching {
                    val stops = container.cloudDataSource?.fetchTodayStops(collectorUid, today).orEmpty()
                    container.outletRepository.getByIds(stops.map { it.outletId })
                }.getOrNull()

                // On a failed fetch keep whatever set we already had rather than
                // clearing it — losing the geofence mid-shift because of one flaky
                // request would silently stop recording visits.
                if (outlets != null) {
                    geofenceManager.setAssignedOutlets(outlets)
                    assignedOutletCount = outlets.size
                }
                delay(PLAN_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun stopTracking() {
        fusedClient.removeLocationUpdates(locationCallback)
        compass.stop()
        stationaryDetector.reset()
        lastPushedHeading = null
        planWatcher?.cancel()
        planWatcher = null

        // Partial update only (never setValue here) so the collector's last known
        // lat/lng stays on the node — they should still appear on the map, just
        // marked offline at their last position, not disappear entirely.
        val uid = currentCollectorUid
        if (uid != null) {
            serviceScope.launch {
                runCatching { container.cloudDataSource?.updateLiveLocationStatus(uid, "offline") }
            }
        }

        statePrefs.edit().clear().apply()
        currentShiftId = null
        currentCollectorId = null
        currentCollectorUid = null
        appliedMode = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleLocation(location: Location) {
        val shiftId = currentShiftId ?: return
        val collectorId = currentCollectorId ?: return
        val collectorUid = currentCollectorUid ?: return
        val mode = classifier.accept(if (location.hasSpeed()) location.speed else 0f)
        if (mode != appliedMode) applyTrackingMode(mode)

        // Accuracy gate, teleport rejection, stationary-jitter hold, Kalman position
        // smoothing, and speed EMA all happen here — a null result means the raw fix
        // was too poor or physically implausible to trust, so it's dropped entirely
        // (nothing recorded, nothing pushed to the live map) rather than passed through.
        // Stationary means "hasn't gone anywhere", not "reports a low speed".
        // The speed field is derived from the same noisy positions it is meant to
        // describe, so in a built-up street a parked phone can read several m/s
        // and never reach STOPPED — leaving the jitter suppression switched off
        // precisely when it is needed. Displacement is immune to that, so either
        // signal is enough to call it parked.
        val parked = stationaryDetector.accept(location.latitude, location.longitude, location.time)
        val isStationary = mode == TrackingMode.STOPPED || parked

        val refined = refiner.refine(location, isStationary = isStationary) ?: return
        val lowConfidence = refined.accuracyM > 50f

        val point = TelemetryPointEntity(
            id = UUID.randomUUID().toString(),
            shiftId = shiftId,
            collectorId = collectorId,
            collectorUid = collectorUid,
            lat = refined.lat,
            lng = refined.lng,
            accuracyM = refined.accuracyM,
            speedMps = refined.speedMps,
            bearing = refined.bearing ?: 0f,
            capturedAt = refined.capturedAtMs,
            batteryPct = DeviceState.batteryPercent(this),
            networkState = DeviceState.networkState(this),
            mockFlag = LocationCompat.isMock(location),
            syncStatus = SyncStatus.PENDING.name,
        )

        // Movement threshold + heartbeat back-off. Standing still transmits nothing
        // until a heartbeat falls due, which is what stops a collector parked at a
        // customer from burning data and battery on identical points.
        compass.updateLocation(point.lat, point.lng, timeMs = point.capturedAt)

        val decision = gate.evaluate(point.lat, point.lng, point.capturedAt)

        serviceScope.launch {
            // Geofence and the local shift record run on *every* accepted fix, not
            // just transmitted ones: arrival detection must not wait on a heartbeat,
            // and neither of these costs anything off-device.
            container.shiftRepository.updateLocation(shiftId, point.lat, point.lng)

            val atOutlet = geofenceManager.onLocation(
                shiftId = shiftId,
                collectorId = collectorId,
                collectorUid = collectorUid,
                lat = point.lat,
                lng = point.lng,
                lowConfidence = lowConfidence,
            )

            if (decision.recordTrail) {
                container.telemetryRepository.insert(point)
                TelemetrySyncWorker.enqueue(applicationContext)
            }

            if (decision.sendLive) {
                // Immediate write for the dashboard live map — bypasses the batched
                // WorkManager sync path so the marker stays responsive.
                runCatching {
                    container.cloudDataSource?.writeLiveLocation(
                        collectorUid,
                        mapOf(
                            "lat" to point.lat,
                            "lng" to point.lng,
                            "accuracy" to point.accuracyM,
                            "speed" to point.speedMps * 3.6,
                            "bearing" to refined.bearing,
                            "heading" to latestHeading,
                            "updated_at" to com.google.firebase.database.ServerValue.TIMESTAMP,
                            "shift_id" to shiftId,
                            "status" to if (atOutlet) "at_outlet" else mode.liveStatus(),
                            "collector_name" to (currentCollectorName ?: collectorId),
                            "team_id" to container.sessionRepository.currentSession()?.teamId,
                        ),
                    )
                }
            }
        }

        // Surfacing the geofence count matters: if today's assignment is empty the
        // app will never auto check-in, and the collector should be able to see that
        // from the notification rather than discovering it at an outlet.
        //
        // The transmit state is shown for the same reason — while parked, the app
        // deliberately goes quiet, and without saying so a collector has no way to
        // tell "saving data" apart from "tracking broken".
        val plan = if (assignedOutletCount > 0) "$assignedOutletCount outlet" else "belum ada jadwal"
        val transmit = when (decision.reason) {
            TelemetryGate.Reason.MOVED -> "mengirim"
            TelemetryGate.Reason.HEARTBEAT -> "hemat data"
            TelemetryGate.Reason.SUPPRESSED -> "diam • hemat data"
        }
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification("${mode.label()} • $transmit • $plan"),
        )
    }

    private fun applyTrackingMode(mode: TrackingMode, force: Boolean = false) {
        if (!force && mode == appliedMode) return
        if (!hasLocationPermission()) return
        appliedMode = mode

        val policy = when (mode) {
            TrackingMode.MOVING -> TrackingPolicy.Moving
            TrackingMode.SLOW -> TrackingPolicy.Slow
            TrackingMode.STOPPED -> TrackingPolicy.Stopped
        }

        val request = LocationRequest.Builder(
            // Drop off the GPS radio while parked — see TrackingPolicy.highAccuracy.
            if (policy.highAccuracy) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            },
            policy.intervalMs,
        )
            .setMinUpdateIntervalMillis(policy.minIntervalMs)
            // The OS itself discards fixes under this displacement, so the app isn't
            // even woken for them. Cheapest part of the movement threshold.
            .setMinUpdateDistanceMeters(policy.minDistanceM)
            // True = the very first fix waits for a high-quality reading instead of
            // returning whatever coarse (e.g. cell-tower) fix is available immediately.
            .setWaitForAccurateLocation(policy.highAccuracy)
            .build()

        fusedClient.removeLocationUpdates(locationCallback).addOnCompleteListener {
            if (hasLocationPermission() && currentShiftId != null) {
                fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle("Collection Field • Shift aktif")
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.tracking_channel_description)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun TrackingMode.label(): String = when (this) {
        TrackingMode.MOVING -> "Bergerak"
        TrackingMode.SLOW -> "Bergerak pelan"
        TrackingMode.STOPPED -> "Berhenti"
    }

    /** Maps to the dashboard's CollectorStatus ("moving"|"stopped"|"at_outlet"); "at_outlet" is set by the geofence check. */
    private fun TrackingMode.liveStatus(): String = when (this) {
        TrackingMode.MOVING, TrackingMode.SLOW -> "moving"
        TrackingMode.STOPPED -> "stopped"
    }

    companion object {
        private const val ACTION_START = "com.collectionfield.app.action.START_TRACKING"
        private const val ACTION_STOP = "com.collectionfield.app.action.STOP_TRACKING"
        private const val EXTRA_SHIFT_ID = "shift_id"
        private const val EXTRA_COLLECTOR_ID = "collector_id"
        private const val EXTRA_COLLECTOR_UID = "collector_uid"
        private const val CHANNEL_ID = "shift_tracking"
        private const val NOTIFICATION_ID = 1001


        // How often to re-read today's assignment so mid-shift plan edits take effect.
        private const val PLAN_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

        // A turn worth telling the dashboard about, and the floor between pushes.
        // At 20 degrees / 3 s, continuously spinning costs at most 20 writes a
        // minute; standing still costs none at all.
        private const val HEADING_PUSH_DEGREES = 20f
        private const val HEADING_PUSH_MIN_INTERVAL_MS = 3_000L

        fun start(context: Context, shiftId: String, collectorId: String, collectorUid: String) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SHIFT_ID, shiftId)
                putExtra(EXTRA_COLLECTOR_ID, collectorId)
                putExtra(EXTRA_COLLECTOR_UID, collectorUid)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
