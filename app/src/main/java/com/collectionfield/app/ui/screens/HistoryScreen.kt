package com.collectionfield.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
            }
            Column {
                Text("Riwayat Shift", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${state.shifts.size} shift tercatat di perangkat ini",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        if (state.shifts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                    Text(
                        "Belum ada riwayat shift. Mulai shift pertama Anda dari halaman utama.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.shifts, key = { it.id }) { shift ->
                    val isActive = shift.status == "ACTIVE"
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    color = if (isActive) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = if (isActive) "Sedang Berlangsung" else "Selesai",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                                if (!isActive && shift.endedAt != null) {
                                    Text(
                                        formatDuration(shift.endedAt - shift.startedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                                Column {
                                    Text("Mulai: ${formatDateTime(shift.startedAt)}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "Selesai: ${shift.endedAt?.let(::formatDateTime) ?: "Masih berjalan"}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }

                            if (shift.lastLat != null && shift.lastLng != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    )
                                    Text(
                                        "Lokasi terakhir: %.5f, %.5f".format(shift.lastLat, shift.lastLng),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun formatDuration(durationMs: Long): String {
    val totalMinutes = (durationMs.coerceAtLeast(0) / 60_000)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return "${h}j ${m}m"
}
