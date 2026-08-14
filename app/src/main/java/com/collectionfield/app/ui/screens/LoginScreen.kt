/**
 * THESIS: The login is a formal duty registration — a "soft receipt" the collector stamps
 * with their own name, not a cold technical console. Precision (date/time/version, monospace
 * meta) signals reliability; the language and tone stay warm and Indonesian throughout.
 *
 * OWN-WORLD: Paper white ground, monospace typography for data/meta only (date, time,
 * version), dotted line dividers, Indigo 500 accent, 16dp radius consistent with the rest
 * of the app.
 *
 * STORY: The collector identifies themselves and "stamps" their entry into the system.
 */

package com.collectionfield.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.collectionfield.app.domain.CollectorSession
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoggedIn: (CollectorSession) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val currentTime = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) }
    val currentDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(60.dp))

            // --- RECEIPT HEADER ---
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "COLLECTION FIELD",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                DottedDivider(Modifier.padding(vertical = 12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ReceiptMetaItem("TANGGAL", currentDate)
                    ReceiptMetaItem("JAM", currentTime)
                    ReceiptMetaItem("VERSI", "0.2.0")
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Masuk untuk Mulai Bertugas",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 26.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Gunakan ID Karyawan dan PIN yang diberikan admin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(32.dp))

            // --- LOGIN FORM ---
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(1000)) + slideInVertically(tween(800)) { it / 4 }
            ) {
                var pinVisible by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(2.dp, RoundedCornerShape(16.dp))
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(16.dp)
                        )
                        .padding(20.dp)
                ) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("ID Karyawan") },
                        placeholder = { Text("Contoh: FLD-101") },
                        leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                        singleLine = true,
                        enabled = !state.isLoading,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Kode PIN") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { pinVisible = !pinVisible }) {
                                Icon(
                                    imageVector = if (pinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (pinVisible) "Sembunyikan PIN" else "Tampilkan PIN",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        },
                        visualTransformation = if (pinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !state.isLoading,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (!state.isLoading) viewModel.login(code, pin, onLoggedIn) }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    state.error?.let {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = { viewModel.login(code, pin, onLoggedIn) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = state.firebaseReady && !state.isLoading && code.isNotBlank() && pin.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 2.dp
                        )
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "Masuk",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
            
            // --- FOOTER ---
            DottedDivider()
            Spacer(Modifier.height(16.dp))
            
            Text(
                text = if (state.firebaseReady) {
                    "Sistem siap digunakan"
                } else {
                    "Tidak dapat terhubung ke server. Hubungi admin."
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.firebaseReady)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.error,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Collection Field App",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(bottom = 32.dp)
            )
        }
    }
}

@Composable
fun ReceiptMetaItem(label: String, value: String) {
    Column {
        Text(
            label, 
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontSize = 9.sp
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun DottedDivider(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outline
    Canvas(
        modifier
            .fillMaxWidth()
            .height(1.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
        )
    }
}
