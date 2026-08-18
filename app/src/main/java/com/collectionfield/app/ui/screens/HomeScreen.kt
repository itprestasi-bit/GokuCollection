/**
 * The collector's shift screen.
 *
 * Reading order follows what the collector needs, in the order they need it:
 * shift state first (am I on the clock?), then the open visit if there is one
 * (the only thing they can act on right now), then live telemetry as
 * reassurance that tracking is working, then today's route.
 *
 * Visual system is the Prestasi Group mark's two colours — crimson #dc214c on a
 * warm off-white — shared with the admin dashboard, so a supervisor moving
 * between the web Command Center and a collector's phone sees one product.
 * Colours come from the theme's roles, never literals; see ui/theme/Color.kt.
 */

package com.collectionfield.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.collectionfield.app.data.local.ShiftEntity
import com.collectionfield.app.data.repository.ThemePreferences
import com.collectionfield.app.domain.CollectorSession
import com.collectionfield.app.domain.VisitOutlet
import com.collectionfield.app.location.LocationTrackingService
import com.collectionfield.app.ui.components.OutletDetailDialog
import com.collectionfield.app.ui.components.StatusBadge
import com.collectionfield.app.util.LocationPermissions
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    session: CollectorSession,
    viewModel: HomeViewModel,
    visitActionViewModel: VisitActionViewModel,
    themePreferences: ThemePreferences,
    onOpenOutlets: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDailyPlan: () -> Unit,
    onOpenRouteMap: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isDarkMode by themePreferences.isDarkMode.collectAsStateWithLifecycle()
    var pendingPermissionStart by remember { mutableStateOf(false) }
    var selectedOutlet by remember { mutableStateOf<VisitOutlet?>(null) }
    var showVisitResultDialog by remember { mutableStateOf(false) }
    var showEndShiftConfirm by remember { mutableStateOf(false) }
    var showLocationPermissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (pendingPermissionStart) {
            pendingPermissionStart = false
            if (LocationPermissions.hasForegroundLocation(context)) {
                startShift(context, viewModel)
            } else {
                showLocationPermissionDenied = true
            }
        }
    }

    // cloudMessage carries both the outlet-sync result and the "this outlet isn't on
    // today's schedule" rejection. It was previously computed and never rendered, so
    // a rejected manual check-in just appeared to do nothing.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.cloudMessage) {
        val message = state.cloudMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // --- PROFILE HEADER ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Collection Field",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = session.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = { themePreferences.setDarkMode(it) },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    IconButton(
                        onClick = {
                            if (state.activeShift == null) {
                                viewModel.logout()
                                onLoggedOut()
                            }
                        },
                        modifier = Modifier.alpha(if (state.activeShift == null) 1f else 0.4f)
                    ) {
                        Icon(Icons.Default.Logout, contentDescription = "Keluar", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // --- SHIFT CONTROL (THE SOFT STAMP) ---
            ShiftCard(
                shift = state.activeShift,
                onStart = {
                    if (LocationPermissions.hasForegroundLocation(context)) {
                        startShift(context, viewModel)
                    } else {
                        pendingPermissionStart = true
                        permissionLauncher.launch(LocationPermissions.requestablePermissions())
                    }
                },
                onEnd = { showEndShiftConfirm = true },
            )

            // --- CHECK-IN: open visit detected by the GPS geofence ---
            val openOutlet = state.openVisitOutlet
            val openVisit = state.openVisit
            if (openOutlet != null && openVisit != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(16.dp)),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("SEDANG DI LOKASI", style = MaterialTheme.typography.labelSmall)
                            Text(openOutlet.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Button(onClick = { showVisitResultDialog = true }, shape = RoundedCornerShape(12.dp)) {
                            Text("SELESAI KUNJUNGAN")
                        }
                    }
                }
            }

            // --- LIVE TELEMETRY ---
            if (state.activeShift != null) {
                LiveTelemetryCard(state)
            } else {
                // Show offline status summary
                Surface(
                    modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(16.dp)),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MetricItem("BATERAI", "${state.currentBatteryPct.coerceAtLeast(0)}%", Icons.Default.BatteryChargingFull)
                        MetricItem("SINYAL", state.currentNetworkState, Icons.Default.SignalCellularAlt)
                        MetricItem("STATUS", if (state.pendingSync > 0) "SINKRONISASI" else "TERHUBUNG", Icons.Default.CloudDone)
                    }
                }
            }

            // --- TODAY'S TARGET OUTLETS ---
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("RENCANA HARI INI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text(
                        "LIHAT SEMUA", 
                        style = MaterialTheme.typography.labelSmall, 
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onOpenDailyPlan() }
                    )
                }
                
                val outlets = state.visitPlan?.outlets ?: emptyList()
                if (outlets.isEmpty()) {
                    // Empty here has a specific consequence — no auto check-in today —
                    // so it says that instead of just "no plan".
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "Belum ada jadwal hari ini",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "Check-in otomatis aktif hanya di outlet yang dijadwalkan admin.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    // A vertical stack, not a horizontal carousel: a sideways swipe on
                    // the main content fights the system back-gesture, and it hides how
                    // many stops are left — the number a collector most wants to know.
                    // The first three are shown here; "Lihat semua" opens the full route.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        outlets.take(3).forEach { outlet ->
                            StopSummaryRow(outlet, onClick = { selectedOutlet = outlet })
                        }
                        if (outlets.size > 3) {
                            Text(
                                "+${outlets.size - 3} outlet lainnya",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenDailyPlan() }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }

            // --- SECONDARY ACTIONS ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DashboardActionItem(
                    // No longer the whole outlet master list — this screen is scoped
                    // to today's assignment, so the label says so.
                    title = "OUTLET",
                    subtitle = "Tugas hari ini",
                    icon = Icons.Default.Storefront,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenOutlets
                )
                DashboardActionItem(
                    title = "RIWAYAT",
                    subtitle = "Riwayat shift",
                    icon = Icons.Default.History,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenHistory
                )
                DashboardActionItem(
                    title = "RUTE",
                    subtitle = "Rute hari ini",
                    icon = Icons.Default.Route,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenRouteMap
                )
            }

            // --- SYSTEM INFO ---
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "STATUS: ${if (state.pendingSync > 0) "Menyinkronkan data..." else "Semua data tersimpan"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Text(
                    text = "ID Karyawan: ${session.employeeCode} • v0.2.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    fontSize = 9.sp
                )
            }
        }
    }

    selectedOutlet?.let { outlet ->
        OutletDetailDialog(
            outlet,
            onDismiss = { selectedOutlet = null },
            onManualCheckIn = if (state.activeShift != null) {
                { viewModel.manualCheckIn(outlet.outletId) }
            } else null,
        )
    }

    val dialogOutlet = state.openVisitOutlet
    val dialogVisit = state.openVisit
    if (showVisitResultDialog && dialogOutlet != null && dialogVisit != null) {
        VisitResultDialog(
            outlet = dialogOutlet,
            visitId = dialogVisit.id,
            collectorUid = session.uid,
            viewModel = visitActionViewModel,
            onDismiss = { showVisitResultDialog = false },
            onSubmitted = { showVisitResultDialog = false },
        )
    }

    if (showEndShiftConfirm) {
        AlertDialog(
            onDismissRequest = { showEndShiftConfirm = false },
            icon = { Icon(Icons.Default.Timeline, contentDescription = null) },
            title = { Text("Akhiri Shift Sekarang?") },
            text = {
                Text(
                    "Pelacakan GPS akan berhenti dan status Anda akan tampil offline di dashboard admin. " +
                        "Pastikan semua kunjungan hari ini sudah selesai dicatat."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEndShiftConfirm = false
                        LocationTrackingService.stop(context)
                        viewModel.endShift { }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Ya, Akhiri Shift")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndShiftConfirm = false }) {
                    Text("Batal")
                }
            },
        )
    }

    if (showLocationPermissionDenied) {
        AlertDialog(
            onDismissRequest = { showLocationPermissionDenied = false },
            icon = { Icon(Icons.Default.LocationOff, contentDescription = null) },
            title = { Text("Izin Lokasi Diperlukan") },
            text = {
                Text(
                    "Aplikasi ini butuh akses lokasi untuk melacak kunjungan dan check-in otomatis. " +
                        "Aktifkan izin lokasi di Pengaturan agar shift bisa dimulai."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showLocationPermissionDenied = false
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Buka Pengaturan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationPermissionDenied = false }) {
                    Text("Nanti Saja")
                }
            },
        )
    }
}

@Composable
private fun LiveTelemetryCard(state: HomeUiState) {
    val point = state.latestPoint
    Surface(
        modifier = Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Radar, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("PELACAKAN AKTIF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("KOORDINAT", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    Text(
                        text = point?.let { "${it.lat.format(5)}, ${it.lng.format(5)}" } ?: "Menunggu sinyal GPS...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                }
                MetricItem("KECEPATAN", point?.let { "${(it.speedMps * 3.6f).format(1)}km/j" } ?: "-", Icons.Default.Speed)
                MetricItem("AKURASI", point?.let { "±${it.accuracyM.toInt()}m" } ?: "-", Icons.Default.GpsFixed)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricItem("BATERAI", "${state.currentBatteryPct.coerceAtLeast(0)}%", Icons.Default.BatteryChargingFull)
                MetricItem("SINYAL", state.currentNetworkState, Icons.Default.SignalCellularAlt)
                MetricItem("SINKRON", if (state.pendingSync > 0) "TERTUNDA" else "TERSINKRON", Icons.Default.CloudDone)
            }
        }
    }
}

/** One stop on today's route: sequence, name, address, status — full width so the
 *  outlet name has room to be read rather than truncated to fit a 200dp card. */
@Composable
private fun StopSummaryRow(outlet: VisitOutlet, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Route order is the thing that tells a collector where they are in the
            // day, so it leads the row instead of being buried in the body text.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(26.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        outlet.urutanRute.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    outlet.namaOutlet,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    outlet.alamat,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(8.dp))
            StatusBadge(outlet.status)
        }
    }
}

@Composable
private fun ShiftCard(
    shift: ShiftEntity?,
    onStart: () -> Unit,
    onEnd: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(shift?.id) {
        while (shift != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val isActive = shift != null
    val containerColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        animationSpec = tween(500)
    )
    val contentColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(500)
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(if (isActive) 8.dp else 2.dp, RoundedCornerShape(16.dp))
            .background(containerColor, RoundedCornerShape(16.dp))
            .clickable { if (!isActive) onStart() }
            .padding(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isActive) "SEDANG BERTUGAS" else "SIAP BERTUGAS",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                    Text(
                        text = if (isActive) formatDuration(now - shift.startedAt) else "Belum Mulai Shift",
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 32.sp),
                        color = contentColor
                    )
                }
                
                Surface(
                    shape = CircleShape,
                    color = contentColor.copy(alpha = 0.1f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isActive) Icons.Default.Timeline else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = contentColor
                        )
                    }
                }
            }

            if (isActive) {
                Button(
                    onClick = onEnd,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("AKHIRI SHIFT", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Text(
                    "Ketuk untuk memulai pelacakan GPS dan sinkronisasi kerja.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun DashboardActionItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, icon: ImageVector) {
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    }
}

private fun startShift(context: Context, viewModel: HomeViewModel) {
    viewModel.startShift { shift ->
        LocationTrackingService.start(context, shift.id, shift.collectorId, shift.collectorUid)
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0) / 1_000
    val h = totalSeconds / 3_600
    val m = (totalSeconds % 3_600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun Double.format(decimals: Int): String = String.format(Locale.US, "%.${decimals}f", this)
private fun Float.format(decimals: Int): String = String.format(Locale.US, "%.${decimals}f", this)


