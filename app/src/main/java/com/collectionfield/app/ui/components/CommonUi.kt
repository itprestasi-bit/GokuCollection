package com.collectionfield.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.collectionfield.app.domain.VisitOutlet
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun StatusBadge(status: String) {
    val color = when (status) {
        "SELESAI" -> Color(0xFF10B981) // Emerald 500
        "PENDING" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    val label = when (status) {
        "PENDING" -> "BELUM DIKUNJUNGI"
        else -> status
    }
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = color
        )
    }
}

@Composable
fun OutletDetailDialog(
    outlet: VisitOutlet,
    onDismiss: () -> Unit,
    onManualCheckIn: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale("in", "ID"))

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val gmmIntentUri = Uri.parse("google.navigation:q=${outlet.latitude},${outlet.longitude}")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                    mapIntent.setPackage("com.google.android.apps.maps")
                    context.startActivity(mapIntent)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Directions, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("NAVIGASI")
            }
        },
        dismissButton = {
            Row {
                if (onManualCheckIn != null) {
                    TextButton(onClick = { onManualCheckIn(); onDismiss() }) { Text("CHECK-IN MANUAL") }
                }
                TextButton(onClick = onDismiss) { Text("TUTUP") }
            }
        },
        title = { 
            Text(
                outlet.namaOutlet, 
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            ) 
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow("ALAMAT", outlet.alamat)
                DetailRow("LOKASI", "${String.format(Locale.US, "%.5f", outlet.latitude)}, ${String.format(Locale.US, "%.5f", outlet.longitude)}")

                outlet.piutangItems.forEach { item ->
                    val overdue = item.jatuhTempo?.let { isOverdue(it) } ?: false
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            DetailRow("PIUTANG ${item.tag}", currencyFormat.format(item.amount))
                        }
                        Column(Modifier.weight(1f)) {
                            DetailRow("JATUH TEMPO", item.jatuhTempo ?: "-")
                        }
                    }
                    if (overdue) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${item.tag}: OVERDUE (PAST DUE)",
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Column {
        Text(
            label, 
            style = MaterialTheme.typography.labelSmall, 
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            fontSize = 9.sp
        )
        Text(
            value, 
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

fun isOverdue(dateStr: String): Boolean {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dueDate = sdf.parse(dateStr)
        dueDate?.before(Date()) ?: false
    } catch (e: Exception) {
        false
    }
}
