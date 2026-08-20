package com.wlftest.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Data ---

data class LogEntry(
    val time: Long,
    val tag: String,
    val message: String,
)

// --- Collector (accesible desde cualquier lugar) ---

object LogCollector {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun clear() { _entries.value = emptyList() }

    fun log(tag: String, msg: String) {
        val entry = LogEntry(System.currentTimeMillis(), tag, msg)
        // update{} es atómico — multiple coroutines pueden llamar log() en paralelo
        // sin perder entradas (el viejo `value = value + entry` tenía race condition).
        _entries.update { it + entry }
    }
}

// --- Colors por tag ---

private fun tagColor(tag: String): Color = when (tag.uppercase()) {
    "INFO", "MATCH" -> Color(0xFF4CAF50)       // verde
    "SUCCESS" -> Color(0xFF00E676)               // verde brillante
    "ERROR" -> Color(0xFFFF5252)                 // rojo
    "REQUEST" -> Color(0xFFFFC107)               // amarillo
    "RESPONSE" -> Color(0xFF29B6F6)              // cyan
    "DECRYPT", "ENCRYPT" -> Color(0xFFCE93D8)  // púrpura
    "PARSE" -> Color(0xFFFF8A65)                // naranja
    else -> Color(0xFF90A4AE)                    // gris
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

// --- UI Composable ---

@Composable
fun DebugLogPanel(
 entries: List<LogEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "DEBUG LOG",
                color = Color(0xFF90A4AE),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${entries.size}",
                color = Color(0xFF607D8B),
                fontSize = 11.sp,
            )
            IconButton(
                onClick = {
                    val allText = entries.joinToString("\n") { e ->
                        "${timeFormat.format(Date(e.time))} [${e.tag}] ${e.message}"
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("debug_log", allText))
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(Icons.Default.ContentCopy, "Copiar todo", tint = Color(0xFF607D8B), modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, "Limpiar", tint = Color(0xFF607D8B), modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Colapsar" else "Expandir",
                    tint = Color(0xFF607D8B),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Log entries
        AnimatedVisibility(visible = expanded) {
 if (entries.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Sin logs aún. Busca servidores.", color = Color(0xFF546E7A), fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(entries) { index, entry ->
                        LogEntryRow(entry) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("log", entry.message))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry, onCopy: () -> Unit) {
    val timeStr = timeFormat.format(Date(entry.time))
    val color = tagColor(entry.tag)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF0D0D1A))
            .padding(start = 8.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Color(0xFF546E7A), fontSize = 10.sp, fontFamily = FontFamily.Monospace)) {
                    append(timeStr)
                }
                append(" ")
                withStyle(SpanStyle(color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)) {
                    append("[${entry.tag}]")
                }
                append(" ")
                withStyle(SpanStyle(color = Color(0xFFCFD8DC), fontSize = 10.sp, fontFamily = FontFamily.Monospace)) {
                    append(entry.message)
                }
            },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.ContentCopy, "Copiar", tint = Color(0xFF455A64), modifier = Modifier.size(12.dp))
        }
    }
}