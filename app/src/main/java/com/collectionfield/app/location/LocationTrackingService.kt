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
import com.collectionfield.app.util.DeviceState
import com.collectionfield.app.util.MovementClassifier
import com.collectionfield.app.sync.TelemetrySyncWorker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

class LocationTrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var notificationManager: NotificationManager
    private val classifier = MovementClassifier()

    private var currentShiftId: String? = null
    private var currentCollectorId: String? = null
    private var currentCollectorUid: String? = null
    private var currentCollectorName: String? = null
    private var appliedMode: TrackingMode? = null
    private var lastTelemetryRecordedAtMs = 0L

    private val container by lazy { (application as CollectionFieldApplication).container }
    private val statePrefs by lazy { getSharedPreferences("tracking_service", Context.MODE_PRIVATE) }
    private val geofenceManager by lazy { GeofenceVisitManager(container.outletRepository, container.visitRepository) }

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

        // Immediate optimistic status so the collector shows as "moving" on the
        // admin map right away, instead of waiting for the first GPS fix (or
        // lingering on whatever status was left over from their last shift).
        serviceScope.launch {
            runCatching { container.cloudDataSource?.updateLiveLocationStatus(collectorUid, "moving") }
        }
    }

    private fun stopTracking() {
        fusedClient.removeLocationUpdates(locationCallback)

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

        val accuracyM = if (location.hasAccuracy()) location.accuracy else -1f
        val lowConfidence = accuracyM < 0f || accuracyM > 50f

        val point = TelemetryPointEntity(
            id = UUID.randomUUID().toString(),
            shiftId = shiftId,
            collectorId = collectorId,
            collectorUid = collectorUid,
            lat = location.latitude,
            lng = location.longitude,
            accuracyM = accuracyM,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            bearing = if (location.hasBearing()) location.bearing else 0f,
            capturedAt = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            batteryPct = DeviceState.batteryPercent(this),
            networkState = DeviceState.networkState(this),
            mockFlag = LocationCompat.isMock(location),
            syncStatus = SyncStatus.PENDING.name,
        )

        serviceScope.launch {
            container.shiftRepository.updateLocation(shiftId, point.lat, point.lng)

            val atOutlet = geofenceManager.onLocation(
                shiftId = shiftId,
                collectorId = collectorId,
                collectorUid = collectorUid,
                lat = point.lat,
                lng = point.lng,
                lowConfidence = lowConfidence,
            )

            // The GPS fix rate (as low as every 3s while moving) drives the live map
            // below unconditionally — RTDB bills by bandwidth, not per-write, so that's
            // cheap. The durable Firestore telemetry trail is billed per document
            // written, so it stays throttled to its own cadence regardless of how often
            // fixes arrive, to keep it from scaling 1:1 with the live-tracking interval.
            val now = System.currentTimeMillis()
            if (now - lastTelemetryRecordedAtMs >= TELEMETRY_MIN_INTERVAL_MS) {
                lastTelemetryRecordedAtMs = now
                container.telemetryRepository.insert(point)
                TelemetrySyncWorker.enqueue(applicationContext)
            }

            // Immediate write for the dashboard live map — bypasses the batched WorkManager
            // sync path so the marker stays as responsive as the GPS fix rate allows.
            runCatching {
                container.cloudDataSource?.writeLiveLocation(
                    collectorUid,
                    mapOf(
                        "lat" to point.lat,
                        "lng" to point.lng,
                        "accuracy" to point.accuracyM,
                        "speed" to point.speedMps * 3.6,
                        "updated_at" to com.google.firebase.database.ServerValue.TIMESTAMP,
                        "shift_id" to shiftId,
                        "status" to if (atOutlet) "at_outlet" else mode.liveStatus(),
                        "collector_name" to (currentCollectorName ?: collectorId),
                        "team_id" to container.sessionRepository.currentSession()?.teamId,
                    ),
                )
            }
        }

        val accuracy = if (point.accuracyM >= 0) "±${point.accuracyM.toInt()} m" else "akurasi n/a"
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification("${mode.label()} • $accuracy • ${point.networkState.lowercase()}"),
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

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, policy.intervalMs)
            .setMinUpdateIntervalMillis(policy.minIntervalMs)
            .setMinUpdateDistanceMeters(policy.minDistanceM)
            // True = the very first fix waits for a high-quality reading instead of
            // returning whatever coarse (e.g. cell-tower) fix is available immediately.
            .setWaitForAccurateLocation(true)
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

        // Keeps the durable Firestore telemetry trail's write rate independent of the
        // GPS fix interval — see the comment in handleLocation() for why.
        private const val TELEMETRY_MIN_INTERVAL_MS = 15_000L

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
