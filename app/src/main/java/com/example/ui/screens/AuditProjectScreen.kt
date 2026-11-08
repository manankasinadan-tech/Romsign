package com.example.ui.screens

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AuditResult
import com.example.ui.ROMsignUiState
import com.example.ui.ROMsignViewModel
import com.example.ui.components.LiveLogTerminal
import com.example.ui.components.ToneOnToneCard

/**
 * Feature 2 : Audit Project (Analyse Globale & Dépendances).
 * Scans unpacked ROM directories with strict symlink exclusion,
 * analyzes sharedUserId interdependencies, lists invalidated ART caches,
 * and creates deliverables in sdcard/SIGNER/Data/.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditProjectScreen(
    state: ROMsignUiState,
    viewModel: ROMsignViewModel,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Path input card
        ToneOnToneCard(modifier = Modifier.fillMaxWidth().testTag("audit_input_card")) {
            Text(
                text = "Audit Global du Projet ROM",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Parcours récursif (exclusion stricte des symlinks) pour cartographier les signatures et identifier les artefacts ART (.odex, .vdex, .art, .fsvmeta).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = state.auditTargetDir,
                onValueChange = { viewModel.setAuditTargetDir(it) },
                label = { Text("Chemin du répertoire décompressé (UKA / Kitchen)") },
                placeholder = { Text("/data/local/UnpackerSystem/") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("audit_path_input"),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Quick preset chips
            Text(
                text = "RACCOURCIS DE RÉPERTOIRES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PresetChip(label = "/data/local/UnpackerSystem/") {
                    viewModel.setAuditTargetDir("/data/local/UnpackerSystem/")
                }
                PresetChip(label = "/sdcard/UnpackerSystem/") {
                    viewModel.setAuditTargetDir("/sdcard/UnpackerSystem/")
                }
                PresetChip(label = "Générer Démo UKA", isAccent = true) {
                    viewModel.generateSampleEnvironment()
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = { viewModel.launchProjectAudit() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("audit_launch_button"),
                enabled = !state.auditIsRunning && state.auditTargetDir.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "LANCER",
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        // Live Log Terminal
        AnimatedVisibility(
            visible = state.auditIsRunning || state.auditLogs.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            LiveLogTerminal(
                logs = state.auditLogs,
                isRunning = state.auditIsRunning,
                title = "Journal d'Audit & Exclusion Symlinks",
                modifier = Modifier.testTag("audit_terminal")
            )
        }

        // Audit Deliverables & Results Card
        AnimatedVisibility(
            visible = state.auditResult != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            state.auditResult?.let { result ->
                AuditResultDetailsCard(
                    result = result,
                    onViewReport = { path -> viewModel.viewReportFile(path) }
                )
            }
        }
    }

    // Modal Report Viewer
    state.viewingReportContent?.let { (title, content) ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissReportViewer() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F1115))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = content,
                        color = Color(0xFFCDD6F4),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissReportViewer() }) {
                    Text("Fermer")
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
private fun PresetChip(label: String, isAccent: Boolean = false, onClick: () -> Unit) {
    val borderColor = if (isAccent) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val bgColor = if (isAccent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val textColor = if (isAccent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
private fun AuditResultDetailsCard(
    result: AuditResult,
    onViewReport: (String) -> Unit
) {
    ToneOnToneCard(modifier = Modifier.fillMaxWidth().testTag("audit_result_card")) {
        Text(
            text = "Rapport d'Audit et Livrables Générés",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Audit achevé en ${result.executionTimeMs} ms • ${result.symlinksSkipped} lien(s) symbolique(s) ignoré(s)",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF52B788)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Summary Metric Tiles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "APKs Totaux",
                value = result.totalApksScanned.toString(),
                color = MaterialTheme.colorScheme.primary
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "Cœur Système",
                value = result.systemCoreApks.size.toString(),
                color = Color(0xFFE0A96D)
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "Caches ART",
                value = result.invalidatedCaches.size.toString(),
                color = Color(0xFFE57373)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Deliverables in sdcard/SIGNER/Data
        Text(
            text = "LIVRABLES DE SORTIE (sdcard/SIGNER/Data)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        result.reportFilesGenerated.forEach { path ->
            val fileName = path.substringAfterLast("/")
            val isDirectory = path.endsWith("backup")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .clickable { onViewReport(path) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (isDirectory) Color(0xFFE0A96D) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = path,
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (!isDirectory) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Visualiser",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SharedUserId Breakdown
        if (result.sharedUserIdGroups.isNotEmpty()) {
            Text(
                text = "INTERDÉPENDANCES SHARED USER ID",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            result.sharedUserIdGroups.forEach { (uid, members) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE0A96D).copy(alpha = 0.08f))
                        .border(1.dp, Color(0xFFE0A96D).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = uid,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = Color(0xFFE0A96D)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${members.size} APK(s) liés : ${members.joinToString(", ")}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.09f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
