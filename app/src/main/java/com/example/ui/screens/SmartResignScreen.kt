package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.example.model.ResignExecutionResult
import com.example.ui.ROMsignUiState
import com.example.ui.ROMsignViewModel
import com.example.ui.components.CustomKeyCreationDialog
import com.example.ui.components.KeySelectionDialog
import com.example.ui.components.LiveLogTerminal
import com.example.ui.components.ToneOnToneCard

/**
 * Feature 3 : Smart Resign (Résignature & Régénération).
 * Prompts user with key choice dialog (AOSP test keys vs custom keys),
 * surgically resigns system core APKs, purges ART caches, and updates permissions.
 */
@Composable
fun SmartResignScreen(
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
        // Smart Resign Configuration Card
        ToneOnToneCard(modifier = Modifier.fillMaxWidth().testTag("smart_resign_card")) {
            Text(
                text = "Smart Resign (Résignature Chirurgicale)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Application sélective de signature sur les APKs ayant un impact systémique (sharedUserId android.uid.system), purge des caches ART obsolètes et régénération.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = state.resignTargetDir,
                onValueChange = { viewModel.setResignTargetDir(it) },
                label = { Text("Répertoire du projet audité") },
                placeholder = { Text("/data/local/UnpackerSystem/") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("resign_path_input"),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Currently selected key indicator
            state.selectedSigningKey?.let { key ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Clé active : ${key.name}",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "SHA-256 : ${key.certFingerprintSha256.take(23)}...",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.requestSmartResign() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("resign_launch_button"),
                enabled = !state.resignIsRunning && state.resignTargetDir.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "LANCER",
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        // Live Log Terminal during surgical resigning
        AnimatedVisibility(
            visible = state.resignIsRunning || state.resignLogs.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            LiveLogTerminal(
                logs = state.resignLogs,
                isRunning = state.resignIsRunning,
                title = "Flux de Résignature & Purge ART",
                modifier = Modifier.testTag("resign_terminal")
            )
        }

        // Resign Summary Result Card
        AnimatedVisibility(
            visible = state.resignResult != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            state.resignResult?.let { res ->
                ResignSummaryCard(result = res)
            }
        }
    }

    // Key Selection Popup Dialog
    if (state.showKeySelectionDialog) {
        KeySelectionDialog(
            availableKeys = state.availableSigningKeys,
            selectedKey = state.selectedSigningKey,
            onKeySelected = { viewModel.selectSigningKey(it) },
            onOpenCustomCreator = { viewModel.openCustomKeyCreator() },
            onConfirm = { viewModel.confirmAndLaunchSmartResign() },
            onDismiss = { viewModel.dismissKeySelection() }
        )
    }

    // Custom Key Creator Dialog
    if (state.showCustomKeyDialog) {
        CustomKeyCreationDialog(
            onDismiss = { viewModel.dismissCustomKeyCreator() },
            onCreateKey = { alias, cn, org ->
                viewModel.createCustomKey(alias, cn, org)
            }
        )
    }
}

@Composable
private fun ResignSummaryCard(result: ResignExecutionResult) {
    ToneOnToneCard(modifier = Modifier.fillMaxWidth().testTag("resign_summary_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (result.isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (result.isSuccess) Color(0xFF52B788) else Color(0xFFE57373),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = if (result.isSuccess) "Résignature Chirurgicale Réussie" else "Résignature Interrompue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Temps de traitement : ${result.executionTimeMs} ms • Clé : ${result.selectedKeyName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Metrics Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF52B788).copy(alpha = 0.08f))
                    .border(1.dp, Color(0xFF52B788).copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = result.resignedApksCount.toString(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF52B788)
                    )
                    Text(
                        text = "APKs Résignés",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE57373).copy(alpha = 0.08f))
                    .border(1.dp, Color(0xFFE57373).copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = result.purgedCachesCount.toString(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE57373)
                    )
                    Text(
                        text = "Caches Purgés",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = result.updatedPermissionsCount.toString(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Permissions XML",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Backup Location Info
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Column {
                Text(
                    text = "Sauvegarde des originaux :",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = result.backupLocation,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
