package com.charles.livecaptionn.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charles.livecaptionn.LiveCaptionApp
import com.charles.livecaptionn.service.CaptionForegroundService
import com.charles.livecaptionn.service.CaptionRuntimeLine

/**
 * Full-screen caption view meant for the AYN Thor's bottom screen: the game
 * runs on the top display, this activity shows a large, scrolling transcript.
 * Opens on whichever display launched it (the bottom screen when the app is
 * started from there).
 */
class CaptionScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val container = (application as LiveCaptionApp).container
        setContent {
            val runtime by container.runtimeStore.state.collectAsState()
            val settings by container.settingsRepository.settingsFlow.collectAsState(initial = null)
            val history = remember { mutableStateListOf<CaptionRuntimeLine>() }

            // The service only keeps the last few lines; keep a longer
            // history here, updating lines in place as translations arrive.
            LaunchedEffect(runtime.transcriptLines) {
                for (line in runtime.transcriptLines) {
                    val idx = history.indexOfLast { it.id == line.id }
                    if (idx >= 0) {
                        if (history[idx] != line) history[idx] = line
                    } else {
                        history.add(line)
                    }
                }
                while (history.size > MAX_HISTORY) history.removeAt(0)
            }

            var fontSp by remember { mutableStateOf(30f) }
            var showOriginal by remember { mutableStateOf(true) }
            CaptionScreen(
                lines = history,
                running = runtime.running,
                paused = runtime.paused,
                error = runtime.lastError,
                fontSp = fontSp,
                showOriginal = showOriginal && (settings?.showOriginal ?: true),
                onFontSmaller = { fontSp = (fontSp - 4f).coerceAtLeast(14f) },
                onFontBigger = { fontSp = (fontSp + 4f).coerceAtMost(72f) },
                onToggleOriginal = { showOriginal = !showOriginal },
                onClear = { history.clear() },
                onPause = { sendServiceAction(CaptionForegroundService.ACTION_PAUSE_RESUME) },
                onStop = {
                    sendServiceAction(CaptionForegroundService.ACTION_STOP)
                    finish()
                }
            )
        }
    }

    private fun sendServiceAction(action: String) {
        startService(Intent(this, CaptionForegroundService::class.java).setAction(action))
    }

    companion object {
        private const val MAX_HISTORY = 300
    }
}

@Composable
private fun CaptionScreen(
    lines: List<CaptionRuntimeLine>,
    running: Boolean,
    paused: Boolean,
    error: String?,
    fontSp: Float,
    showOriginal: Boolean,
    onFontSmaller: () -> Unit,
    onFontBigger: () -> Unit,
    onToggleOriginal: () -> Unit,
    onClear: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit
) {
    val listState = rememberLazyListState()
    val lastText = lines.lastOrNull()?.let { it.originalText + it.translatedText }
    LaunchedEffect(lines.size, lastText) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    val btn = Color(0xFFB0BEC5)
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    !running -> "● Durdu"
                    paused -> "❚❚ Duraklatıldı"
                    else -> "● Dinleniyor"
                },
                color = if (running && !paused) Color(0xFF4CAF50) else Color(0xFFFFB300),
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onFontSmaller) { Text("A−", color = btn, fontSize = 16.sp) }
            TextButton(onClick = onFontBigger) { Text("A+", color = btn, fontSize = 16.sp) }
            TextButton(onClick = onToggleOriginal) {
                Text(if (showOriginal) "Orijinali gizle" else "Orijinali göster", color = btn)
            }
            TextButton(onClick = onClear) { Text("Temizle", color = btn) }
            if (running) {
                TextButton(onClick = onPause) { Text(if (paused) "Devam" else "Duraklat", color = btn) }
                TextButton(onClick = onStop) { Text("Durdur", color = Color(0xFFEF5350)) }
            }
        }
        if (!error.isNullOrBlank()) {
            Text(error, color = Color(0xFFEF5350), fontSize = 13.sp)
        }
        if (lines.isEmpty()) {
            Spacer(Modifier.weight(1f))
            Text(
                "Konuşma bekleniyor…",
                color = Color(0xFF607D8B),
                fontSize = 20.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(lines, key = { it.id }) { line ->
                    val latest = line.id == lines.last().id
                    Column(Modifier.fillMaxWidth()) {
                        if (showOriginal) {
                            Text(
                                line.originalText,
                                color = Color(0xFF78909C),
                                fontSize = (fontSp * 0.55f).sp
                            )
                        }
                        val tr = line.translatedText.ifBlank { if (line.isFinal) "…" else "" }
                        if (tr.isNotEmpty()) {
                            Text(
                                tr,
                                color = if (latest) Color.White else Color(0xFFCFD8DC),
                                fontSize = fontSp.sp,
                                fontWeight = if (latest) FontWeight.SemiBold else FontWeight.Normal,
                                lineHeight = (fontSp * 1.25f).sp
                            )
                        }
                    }
                }
            }
        }
    }
}
