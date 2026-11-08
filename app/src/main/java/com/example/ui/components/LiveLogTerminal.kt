package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Elegant live terminal component with soft monospace styling and auto-scroll.
 */
@Composable
fun LiveLogTerminal(
    logs: List<String>,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
    title: String = "Console d'Exécution"
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    val terminalBg = Color(0xFF0F1115)
    val terminalBorder = Color(0xFF222631)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(terminalBg)
            .border(1.dp, terminalBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE57373)))
            Spacer(modifier = Modifier.width(6.dp))
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFFB74D)))
            Spacer(modifier = Modifier.width(6.dp))
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF81C784)))

            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8F9BAE),
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.weight(1f))

            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = Color(0xFF52B788)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            if (logs.isEmpty() && isRunning) {
                item {
                    Text(
                        text = "Initialisation du processus cryptographique...",
                        color = Color(0xFF6B7280),
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
            items(logs) { log ->
                val textColor = when {
                    log.contains("✓") || log.contains("succès", ignoreCase = true) -> Color(0xFF52B788)
                    log.contains("ERREUR", ignoreCase = true) || log.contains("échec", ignoreCase = true) -> Color(0xFFE57373)
                    log.contains("Avertissement", ignoreCase = true) || log.contains("Ignoré", ignoreCase = true) -> Color(0xFFE0A96D)
                    log.contains("---") || log.contains("===") -> Color(0xFF90CAF9)
                    else -> Color(0xFFCDD6F4)
                }
                Text(
                    text = log,
                    color = textColor,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}
