package com.collectionfield.app.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Surface
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.collectionfield.app.BuildConfig
import com.collectionfield.app.util.LocationPermissions
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

@SuppressLint("MissingPermission")
@Composable
fun RouteMapScreen(
    viewModel: DailyPlanViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }

    fun fetchLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                userLocation = latLng
                viewModel.optimizeRoute(location.latitude, location.longitude)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            permissionDenied = false
            fetchLocation()
        } else {
            permissionDenied = true
        }
    }

    // Reachable directly from the home screen's "Rute Hari Ini" button, which may
    // be the collector's first time granting location — a shift start isn't a
    // prerequisite, so this screen needs its own permission request rather than
    // assuming HomeScreen's shift-start flow already asked.
    LaunchedEffect(Unit) {
        if (LocationPermissions.hasForegroundLocation(context)) {
            fetchLocation()
        } else {
            permissionLauncher.launch(LocationPermissions.requestablePermissions())
        }
    }

    Scaffold { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val summary = if (state.routeDistanceM > 0) {
                "%.1f km · %d menit · %d tujuan".format(
                    state.routeDistanceM / 1000.0,
                    (state.routeDurationSec / 60).coerceAtLeast(1),
                    state.optimizedRoute.size,
                )
            } else null

            val currentLoc = userLocation
            if (permissionDenied) {
                Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Izin lokasi diperlukan untuk menampilkan rute hari ini.",
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            )
                        )
                    }) {
                        Text("Buka Pengaturan")
                    }
                }
            } else if (!BuildConfig.HAS_MAPS_KEY) {
                // A Google map with no key draws its controls and its logo and
                // nothing else — an empty canvas that looks like a broken feature
                // rather than a missing setting. Say which it is.
                Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Peta belum bisa ditampilkan", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Kunci Maps SDK belum dipasang di aplikasi ini. Hubungi admin — " +
                            "isi MAPS_API_KEY di local.properties lalu build ulang.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else if (currentLoc == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                Text("Mencari lokasi...", modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
            } else {
                val cameraPositionState = rememberCameraPositionState {
                    position = CameraPosition.fromLatLngZoom(currentLoc, 12f)
                }
                // Read outside the map's content lambda: that scope uses the
                // GoogleMapComposable applier and can't read MaterialTheme.
                val routeColor = MaterialTheme.colorScheme.primary

                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(isMyLocationEnabled = true),
                    uiSettings = MapUiSettings(zoomControlsEnabled = true)
                ) {
                    // Start Marker
                    Marker(
                        state = MarkerState(position = currentLoc),
                        title = "Mulai (Posisi Saya)",
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                    )

                    // Optimized Route Markers
                    state.optimizedRoute.forEachIndexed { index, outlet ->
                        val pos = LatLng(outlet.latitude, outlet.longitude)
                        Marker(
                            state = MarkerState(position = pos),
                            title = "${index + 1}. ${outlet.namaOutlet}",
                            snippet = outlet.alamat
                        )
                    }

                    // The road route when the server could plan one, straight lines
                    // otherwise. The fallback is deliberate: a line across a river
                    // still shows the order of the stops, and a rider who can see
                    // that can work — an empty map cannot be worked with at all.
                    if (state.routePath.isNotEmpty()) {
                        Polyline(
                            points = state.routePath.map { LatLng(it.first, it.second) },
                            color = routeColor,
                            width = 12f,
                        )
                    } else if (state.optimizedRoute.isNotEmpty()) {
                        Polyline(
                            points = listOf(currentLoc) + state.optimizedRoute.map { LatLng(it.latitude, it.longitude) },
                            color = routeColor,
                            width = 6f,
                            pattern = listOf(Dot(), Gap(12f)),
                        )
                    }
                }

                summary?.let {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 3.dp,
                        shadowElevation = 3.dp,
                    ) {
                        Text(
                            it,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
