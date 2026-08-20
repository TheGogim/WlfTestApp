package com.wlftest.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla de reproducción con ExoPlayer.
 *
 * - Lee PlaybackState desde PlayerSessionHolder.
 * - Primer video se carga automáticamente.
 * - Top bar: back, logs, servers.
 * - Bottom sheet "Servidores": lista de ResolvedServer; tap para cambiar.
 * - Bottom sheet "Logs": todos los logs del sistema, copy-all.
 *
 * Headers (Referer, Origin, etc.) se aplican via DefaultHttpDataSource.Factory
 * porque así el ExoPlayer los usa para TODOS los segmentos HLS, no solo el master.m3u8.
 */
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(navController: NavController) {
    val state by PlayerSessionHolder.state.collectAsState()
    val logs by LogCollector.entries.collectAsState()
    val context = LocalContext.current

    val current = state
    if (current == null) {
        // Sin sesión — volver atrás
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }

    // Video activo — el que se está reproduciendo ahora
    var activeVideo by remember(current) { mutableStateOf(current.activeVideo) }
    var activeServerName by remember(current) { mutableStateOf(current.activeServerName) }

    var showServersSheet by remember { mutableStateOf(false) }
    var showLogsSheet by remember { mutableStateOf(false) }

    // ExoPlayer
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    // Reconfigurar fuente cuando cambia el video activo (cambio de servidor)
    LaunchedEffect(activeVideo) {
        val headers = activeVideo.headers ?: emptyMap()
        val userAgent = headers["User-Agent"]
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        val mediaItem = MediaItem.Builder()
            .setUri(activeVideo.source)
            .build()

        exoPlayer.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        exoPlayer.prepare()
        exoPlayer.play()
    }

    // Liberar ExoPlayer al salir
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(current.title, maxLines = 1, fontSize = 16.sp)
                        if (current.subtitle.isNotEmpty()) {
                            Text(
                                current.subtitle,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        exoPlayer.release()
                        navController.popBackStack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                actions = {
                    // Botón de logs
                    IconButton(onClick = { showLogsSheet = true }) {
                        Icon(Icons.Default.Description, "Ver logs")
                    }
                    // Botón de servidores (con badge de cantidad)
                    BadgedBox(
                        badge = {
                            if (current.availableServers.size > 1) {
                                Badge { Text("${current.availableServers.size}") }
                            }
                        }
                    ) {
                        IconButton(onClick = { showServersSheet = true }) {
                            Icon(Icons.Default.Dns, "Servidores")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
        containerColor = Color.Black,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // --- Bottom sheet: Servidores ---
    if (showServersSheet) {
        ModalBottomSheet(onDismissRequest = { showServersSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Servidores disponibles",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${current.availableServers.size}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(current.availableServers) { server ->
                        ListItem(
                            headlineContent = { Text(server.serverName) },
                            supportingContent = {
                                Text(
                                    "${server.language} • ${server.domain}",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            },
                            trailingContent = {
                                if (server.serverName == activeServerName) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        "Reproduciendo",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                activeVideo = server.video
                                activeServerName = server.serverName
                                showServersSheet = false
                            },
                        )
                    }
                    if (current.availableServers.isEmpty()) {
                        item {
                            Text(
                                "Sin servidores alternativos todavía",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // --- Bottom sheet: Logs del sistema ---
    if (showLogsSheet) {
        ModalBottomSheet(onDismissRequest = { showLogsSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Logs del sistema",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${logs.size}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    IconButton(onClick = {
                        val allText = logs.joinToString("\n") { e ->
                            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                .format(Date(e.time))
                            "$time [${e.tag}] ${e.message}"
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("logs", allText))
                    }) {
                        Icon(Icons.Default.ContentCopy, "Copiar todo")
                    }
                }
                HorizontalDivider()
                if (logs.isEmpty()) {
                    Text(
                        "Sin logs todavía",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp).fillMaxWidth(),
                    ) {
                        items(logs) { entry ->
                            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                .format(Date(entry.time))
                            val color = when (entry.tag.uppercase()) {
                                "INFO", "MATCH" -> Color(0xFF4CAF50)
                                "SUCCESS" -> Color(0xFF00E676)
                                "ERROR" -> Color(0xFFFF5252)
                                "REQUEST" -> Color(0xFFFFC107)
                                "RESPONSE" -> Color(0xFF29B6F6)
                                "DECRYPT", "ENCRYPT" -> Color(0xFFCE93D8)
                                "PARSE" -> Color(0xFFFF8A65)
                                "WARN" -> Color(0xFFFFB74D)
                                else -> Color(0xFF90A4AE)
                            }
                            Text(
                                text = "$time [${entry.tag}] ${entry.message}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = color,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
