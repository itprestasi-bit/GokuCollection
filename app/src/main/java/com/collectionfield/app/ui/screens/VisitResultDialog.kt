package com.collectionfield.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.collectionfield.app.data.local.OutletEntity
import org.json.JSONObject

/** Tags this outlet currently has a receivable for, parsed from its cached piutangJson. */
private fun outletPiutangTags(outlet: OutletEntity): List<String> {
    val json = outlet.piutangJson ?: return emptyList()
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    return obj.keys().asSequence().toList()
}

/**
 * Result-entry sheet for a visit already opened by GPS geofence (or manual fallback).
 * Photo capture is camera-only (no gallery picker) so the evidence can't be an old/stock photo.
 */
@Composable
fun VisitResultDialog(
    outlet: OutletEntity,
    visitId: String,
    collectorUid: String,
    viewModel: VisitActionViewModel,
    onDismiss: () -> Unit,
    onSubmitted: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var notes by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("LUNAS") }
    val piutangTags = remember(outlet.piutangJson) { outletPiutangTags(outlet) }
    var selectedTag by remember(piutangTags) { mutableStateOf(piutangTags.firstOrNull().orEmpty()) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) photo = bitmap
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraLauncher.launch(null)
    }
    val onCapturePhoto = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) cameraLauncher.launch(null) else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(state.success) {
        if (state.success) {
            viewModel.resetState()
            onSubmitted()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!state.isLoading) onDismiss() },
        title = { Text("Selesai Kunjungan: ${outlet.name}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(180.dp).clickable { onCapturePhoto() },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val bmp = photo
                        if (bmp != null) {
                            Image(bmp.asImageBitmap(), contentDescription = "Foto bukti kunjungan", modifier = Modifier.fillMaxSize())
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                                Spacer(Modifier.height(4.dp))
                                Text("Ambil foto bukti (kamera)", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Catatan kunjungan") },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (piutangTags.size > 1) {
                    Column {
                        Text("Piutang yang ditagih", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            piutangTags.forEach { tag ->
                                FilterChip(
                                    selected = selectedTag == tag,
                                    onClick = { selectedTag = tag },
                                    label = { Text(tag) },
                                )
                            }
                        }
                    }
                }

                Column {
                    Text("Status pembayaran", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("LUNAS", "SEBAGIAN", "BELUM_BAYAR").forEach { option ->
                            FilterChip(
                                selected = status == option,
                                onClick = { status = option },
                                label = { Text(option) },
                            )
                        }
                    }
                }

                if (state.error != null) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = photo != null && !state.isLoading,
                onClick = {
                    photo?.let {
                        viewModel.submitVisitResult(
                            context = context,
                            visitId = visitId,
                            outletId = outlet.id,
                            collectorUid = collectorUid,
                            catatan = notes,
                            photo = it,
                            newStatus = status,
                            tag = selectedTag,
                        )
                    }
                },
            ) {
                Text(if (state.isLoading) "Mengirim..." else "KIRIM")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isLoading) { Text("BATAL") }
        },
    )
}
