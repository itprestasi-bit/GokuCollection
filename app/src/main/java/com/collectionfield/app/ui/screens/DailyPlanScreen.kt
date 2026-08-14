package com.collectionfield.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.collectionfield.app.domain.VisitOutlet
import com.collectionfield.app.ui.components.DetailRow
import com.collectionfield.app.ui.components.OutletDetailDialog
import com.collectionfield.app.ui.components.StatusBadge
import com.collectionfield.app.ui.components.isOverdue
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyPlanScreen(
    viewModel: DailyPlanViewModel,
    onViewMap: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedOutlet by remember { mutableStateOf<VisitOutlet?>(null) }

    LaunchedEffect(Unit) {
        viewModel.fetchTodayPlan()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rencana Hari Ini", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onViewMap) {
                        Icon(Icons.Default.Map, contentDescription = "Peta Rute")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (state.error != null) {
                Text("Error: ${state.error}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            } else if (state.plan == null || state.plan!!.outlets.isEmpty()) {
                Text("Tidak ada rencana kunjungan untuk hari ini.", modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.plan!!.outlets) { outlet ->
                        OutletPlanItem(outlet, onClick = { selectedOutlet = outlet })
                        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }

    selectedOutlet?.let {
        OutletDetailDialog(it, onDismiss = { selectedOutlet = null })
    }
}

@Composable
fun OutletPlanItem(outlet: VisitOutlet, onClick: () -> Unit) {
    val currencyFormat = remember { NumberFormat.getCurrencyInstance(Locale("in", "ID")) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(outlet.namaOutlet, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(outlet.alamat, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (outlet.piutangItems.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    outlet.piutangItems.forEach { item ->
                        val overdue = item.jatuhTempo?.let { isOverdue(it) } ?: false
                        Surface(
                            color = if (overdue) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                "${item.tag} ${currencyFormat.format(item.amount)}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            )
                        }
                    }
                }
            }
        }

        StatusBadge(outlet.status)
    }
}

