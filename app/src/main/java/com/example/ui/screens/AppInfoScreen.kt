package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ApkDiagnosis
import com.example.ui.ROMsignUiState
import com.example.ui.ROMsignViewModel
import com.example.ui.components.CryptoBadge
import com.example.ui.components.LiveLogTerminal
import com.example.ui.components.ToneOnToneCard

/**
 * Feature 1 : App Info (Diagnostic Unitaire d'un APK).
 * Dynamically hides inputs during analysis to stream real-time logs,
 * followed by the structured cryptographic trust-chain report.
 */
@Composable
fun AppInfoScreen(
    state: ROMsignUiState,
    viewModel: ROMsignViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onApkSelectedFromUri(uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Dynamic input controls: hidden while running per specifications
        AnimatedVisibility(
            visible = !state.appInfoIsRunning && state.appInfoDiagnosis == null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            ToneOnToneCard(modifier = Modifier.fillMaxWidth().testTag("app_info_input_card")) {
                Text(
                    text = "Diagnostic Unitaire d'un APK",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Identification des schémas V1 à V4, empreintes cryptographiques et chaîne de confiance sharedUserId.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.appInfoSelectedPath,
                        onValueChange = { viewModel.setAppInfoPath(it) },
                        label = { Text("Chemin du fichier .apk") },
                        placeholder = { Text("/sdcard/... ou sélectionner") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("app_info_path_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    IconButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*", "application/vnd.android.package-archive")) },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .testTag("app_info_browse_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Parcourir",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { viewModel.launchAppInfoDiagnosis() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("app_info_verify_button"),
                    enabled = state.appInfoSelectedPath.isNotBlank(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Vérifier",
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Live Log Terminal: displayed during execution
        AnimatedVisibility(
            visible = state.appInfoIsRunning || (state.appInfoLogs.isNotEmpty() && state.appInfoDiagnosis == null),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            LiveLogTerminal(
                logs = state.appInfoLogs,
                isRunning = state.appInfoIsRunning,
                title = "Flux d'Analyse Cryptographique",
                modifier = Modifier.testTag("app_info_terminal")
            )
        }

        // Final Structured Cryptographic Report
        AnimatedVisibility(
            visible = state.appInfoDiagnosis != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            state.appInfoDiagnosis?.let { diag ->
                DiagnosisReportCard(
                    diag = diag,
                    onInspectAnother = { viewModel.resetAppInfo() },
                    onCopy = { text, label ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                        Toast.makeText(context, "$label copié", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
private fun DiagnosisReportCard(
    diag: ApkDiagnosis,
    onInspectAnother: () -> Unit,
    onCopy: (String, String) -> Unit
) {
    ToneOnToneCard(modifier = Modifier.fillMaxWidth().testTag("app_info_report_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = diag.apkName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = diag.packageName ?: "Package inconnu",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onInspectAnother) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Analyser un autre APK",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Signature Schemes Row
        Text(
            text = "SCHÉMAS DE SIGNATURE SUPPORTÉS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CryptoBadge(text = "v1 (JAR)", isActive = diag.schemes.hasV1Jar)
            CryptoBadge(text = "v2 (Block)", isActive = diag.schemes.hasV2Scheme)
            CryptoBadge(text = "v3 (Rotation)", isActive = diag.schemes.hasV3Scheme)
            CryptoBadge(text = "v4 (fs-verity)", isActive = diag.schemes.hasV4Scheme)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Critical Chain of Trust: sharedUserId Analysis
        Text(
            text = "CHAÎNE DE CONFIANCE & IDENTITÉ SYSTÈME",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(6.dp))

        val isSystemUid = diag.hasCriticalSharedUid
        val bannerBg = if (isSystemUid) Color(0xFFE0A96D).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        val bannerBorder = if (isSystemUid) Color(0xFFE0A96D).copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(bannerBg)
                .border(1.dp, bannerBorder, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isSystemUid) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (isSystemUid) Color(0xFFE0A96D) else Color(0xFF52B788),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (diag.sharedUserId.isNullOrBlank()) "Aucun sharedUserId (Processus Isolé)" else "sharedUserId: ${diag.sharedUserId}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isSystemUid) {
                        "Impact Critique : Ce composant partage les permissions et le contexte d'exécution du noyau système. Toute divergence de signature entraînera un bootloop."
                    } else {
                        "Composant autonome : Son espace d'exécution est confiné à son propre UID."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cryptographic Certificates
        Text(
            text = "CERTIFICAT(S) X.509 ET EMPREINTES NUMÉRIQUES",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (diag.certificates.isEmpty()) {
            Text(
                text = "Aucun certificat cryptographique extrait.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            diag.certificates.forEachIndexed { idx, cert ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Identité #${idx + 1} : ${cert.subjectDN}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Algorithme : ${cert.algorithm} • Validité : ${cert.validFrom} → ${cert.validTo}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // SHA-256 Fingerprint
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0F1115))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SHA-256",
                                fontSize = 9.sp,
                                color = Color(0xFF8F9BAE),
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = cert.sha256Fingerprint,
                                fontSize = 10.5.sp,
                                color = Color(0xFFCDD6F4),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(
                            onClick = { onCopy(cert.sha256Fingerprint, "SHA-256") },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copier SHA-256",
                                tint = Color(0xFF8F9BAE),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onInspectAnother,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Analyser un autre package")
        }
    }
}
