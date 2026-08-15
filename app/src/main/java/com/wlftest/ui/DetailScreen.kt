package com.wlftest.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.wlftest.api.TMDb
import com.wlftest.model.*
import com.wlftest.providers.GnulaProvider
import com.wlftest.providers.TioPlusProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DetailViewModel : ViewModel() {
    private val _detail = MutableStateFlow<ShowDetail?>(null)
    val detail: StateFlow<ShowDetail?> = _detail.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Series: temporadas y episodios
    private val _selectedSeason = MutableStateFlow(1)
    val selectedSeason: StateFlow<Int> = _selectedSeason.asStateFlow()

    private val _episodes = MutableStateFlow<List<EpisodeItem>>(emptyList())
    val episodes: StateFlow<List<EpisodeItem>> = _episodes.asStateFlow()

    private val _selectedEpisode = MutableStateFlow<EpisodeItem?>(null)
    val selectedEpisode: StateFlow<EpisodeItem?> = _selectedEpisode.asStateFlow()

    private val _episodesLoading = MutableStateFlow(false)
    val episodesLoading: StateFlow<Boolean> = _episodesLoading.asStateFlow()

    // Servidores — agrupados por provider
    private val _providerServers = MutableStateFlow<Map<String, List<ProviderServer>>>(emptyMap())
    val providerServers: StateFlow<Map<String, List<ProviderServer>>> = _providerServers.asStateFlow()

    private val _serversLoading = MutableStateFlow(false)
    val serversLoading: StateFlow<Boolean> = _serversLoading.asStateFlow()

    // Debug logs
    val logs = LogCollector.entries

    fun load(id: Int, type: ShowType) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _detail.value = TMDb.getDetail(id, type)
                // Si es serie, cargar episodios de la temporada 1 automáticamente
                if (type == ShowType.TV) {
                    selectSeason(1)
                }
            } catch (e: Exception) {
                _error.value = e.message
                e.printStackTrace()
            } finally {
                _loading.value = false
            }
        }
    }

    fun selectSeason(season: Int) {
        _selectedSeason.value = season
        _selectedEpisode.value = null
        _providerServers.value = emptyMap()
        LogCollector.clear()

        val detail = _detail.value ?: return
        viewModelScope.launch {
            _episodesLoading.value = true
            try {
                _episodes.value = TMDb.getSeasonEpisodes(detail.id, season)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _episodesLoading.value = false
            }
        }
    }

    fun selectEpisode(episode: EpisodeItem) {
        _selectedEpisode.value = episode
        _providerServers.value = emptyMap()
        LogCollector.clear()
    }

    fun searchServers() {
        val detail = _detail.value ?: return
        val title = detail.title
        val type = detail.type
        val ep = _selectedEpisode.value

        if (type == ShowType.TV && ep == null) {
            LogCollector.log("ERROR", "Selecciona un episodio primero")
            return
        }

        viewModelScope.launch {
            _serversLoading.value = true
            _providerServers.value = emptyMap()
            LogCollector.clear()

            try {
                // Lanzar ambos providers en paralelo
                val gnulaDeferred = async {
                    try {
                        if (type == ShowType.TV) {
                            GnulaProvider.searchServers(
                                title = title,
                                type = type,
                                seasonNum = _selectedSeason.value,
                                episodeNum = ep!!.episodeNumber,
                            ).map { s ->
                                    ProviderServer("Gnula HD", s.language, s.serverName, s.embedUrl, s.domain)
                                }
                        } else {
                            GnulaProvider.searchServers(title = title, type = type).map { s ->
                                ProviderServer("Gnula HD", s.language, s.serverName, s.embedUrl, s.domain)
                            }
                        }
                    } catch (e: Exception) {
                        LogCollector.log("ERROR", "Gnula: ${e.message}")
                        emptyList()
                    }
                }

                val tioDeferred = async {
                    try {
                        if (type == ShowType.TV) {
                            TioPlusProvider.searchServers(
                                title = title,
                                type = type,
                                seasonNum = _selectedSeason.value,
                                episodeNum = ep!!.episodeNumber,
                            )
                        } else {
                            TioPlusProvider.searchServers(title = title, type = type)
                        }
                    } catch (e: Exception) {
                        LogCollector.log("ERROR", "TioPlus: ${e.message}")
                        emptyList()
                    }
                }

                val gnulaResults = gnulaDeferred.await()
                val tioResults = tioDeferred.await()

                val map = mutableMapOf<String, List<ProviderServer>>()
                if (gnulaResults.isNotEmpty()) map["Gnula HD"] = gnulaResults
                if (tioResults.isNotEmpty()) map["TioPlus"] = tioResults
                _providerServers.value = map

            } finally {
                _serversLoading.value = false
            }
        }
    }

    fun clearLogs() { LogCollector.clear() }
}

// ==================== SCREEN ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    navController: NavController,
    id: Int,
    type: ShowType,
    viewModel: DetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val detail by viewModel.detail.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(id, type) { viewModel.load(id, type) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detalle") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Error", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.load(id, type) }) { Text("Reintentar") }
                }
            }
            detail != null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState),
            ) {
                // Backdrop
                detail!!.backdropUrl?.let { url ->
                    AsyncImage(
                        model = url, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                }

                // Info row: poster + datos
                Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AsyncImage(
                        model = detail!!.posterUrl, contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(120.dp, 180.dp).offset(y = (-40).dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (type == ShowType.MOVIE) Color(0xFFE50914) else Color(0xFF2196F3),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (type == ShowType.MOVIE) Icons.Default.Movie else Icons.Default.Tv,
                                    null, modifier = Modifier.size(14.dp), tint = Color.White,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (type == ShowType.MOVIE) "PELICULA" else "SERIE",
                                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(detail!!.title, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (detail!!.originalTitle != detail!!.title) {
                            Text(detail!!.originalTitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("\u2605 ${"%.1f".format(detail!!.rating)}", color = Color(0xFFFFD700), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            detail!!.year?.let { Spacer(Modifier.width(12.dp)); Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
                            detail!!.runtime?.let { Spacer(Modifier.width(12.dp)); Text("${it}min", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
                            detail!!.numberOfSeasons?.let { Spacer(Modifier.width(12.dp)); Text("$it temp.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp) }
                        }
                        if (detail!!.genres.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                detail!!.genres.take(4).forEach { genre ->
                                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                                        Text(genre, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Sinopsis
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Sinopsis", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(detail!!.overview, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 20.sp)
                }

                // Reparto
                if (detail!!.cast.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text("Reparto", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(detail!!.cast) { member ->
                            Column(modifier = Modifier.width(90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                AsyncImage(
                                    model = member.photoUrl, contentDescription = member.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(70.dp).clip(CircleShape),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(member.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                Text(member.character, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))

                // ===== SECCION SERVIDORES =====
                ServersSection(viewModel)

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ==================== SERVERS SECTION ====================

@Composable
private fun ServersSection(vm: DetailViewModel) {
    val detail by vm.detail.collectAsState()
    val type = detail?.type ?: return
    val selectedSeason by vm.selectedSeason.collectAsState()
    val episodes by vm.episodes.collectAsState()
    val selectedEpisode by vm.selectedEpisode.collectAsState()
    val episodesLoading by vm.episodesLoading.collectAsState()
    val providerServers by vm.providerServers.collectAsState()
    val serversLoading by vm.serversLoading.collectAsState()
    val logs by vm.logs.collectAsState()
    val context = LocalContext.current

    HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)
    Spacer(Modifier.height(16.dp))

    val totalServers = providerServers.values.flatten().size
    Text("SERVIDORES", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
    Text("Gnula HD + TioPlus", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))

    Spacer(Modifier.height(12.dp))

    // --- Series: selector de temporada ---
    if (type == ShowType.TV) {
        Text("Temporada", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val totalSeasons = detail?.numberOfSeasons ?: 1
            items((1..totalSeasons).toList()) { season ->
                val isSelected = season == selectedSeason
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    onClick = { vm.selectSeason(season) },
                ) {
                    Text(
                        "T$season",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        // --- Lista de episodios ---
        Spacer(Modifier.height(12.dp))
        if (episodesLoading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Text("Episodios", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(episodes, key = { it.episodeNumber }) { ep ->
                    val isSelected = selectedEpisode?.episodeNumber == ep.episodeNumber
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { vm.selectEpisode(ep) },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "E${ep.episodeNumber}",
                                color = if (isSelected) Color(0xFF69F0AE) else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                modifier = Modifier.width(36.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    ep.name, color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isSelected) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF69F0AE), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    // --- Botón buscar ---
    val canSearch = type == ShowType.MOVIE || selectedEpisode != null
    Button(
        onClick = { vm.searchServers() },
        enabled = canSearch && !serversLoading,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (serversLoading) Color(0xFF333333) else Color(0xFFE50914),
        ),
    ) {
        if (serversLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Buscando...", color = Color.White)
        } else {
            Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    type == ShowType.MOVIE -> "Buscar en todos los providers"
                    selectedEpisode != null -> "Buscar E${selectedEpisode!!.episodeNumber} en todos"
                    else -> "Selecciona un episodio"
                },
                color = Color.White, fontWeight = FontWeight.Bold,
            )
        }
    }

    // --- Lista de servidores por provider ---
    if (providerServers.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(
            "$totalServers servidor(es) en ${providerServers.size} provider(s)",
            color = Color(0xFF69F0AE), fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))

        providerServers.forEach { (providerName, servers) ->
            // Header del provider
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = when (providerName) {
                    "Gnula HD" -> Color(0xFF1B1B3A)
                    "TioPlus" -> Color(0xFF1A2E1A)
                    else -> Color(0xFF1A1A2E)
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Dns,
                        null,
                        tint = when (providerName) {
                            "Gnula HD" -> Color(0xFFE50914)
                            "TioPlus" -> Color(0xFF4CAF50)
                            else -> Color(0xFF607D8B)
                        },
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        providerName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${servers.size} srv",
                        color = Color(0xFF90A4AE),
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // Servers de este provider
            servers.forEach { server ->
                ProviderServerCard(server) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("embed", server.embedUrl))
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    // --- Debug Log ---
    Spacer(Modifier.height(16.dp))
    DebugLogPanel(
        entries = logs,
        onClear = { vm.clearLogs() },
        modifier = Modifier.padding(bottom = 16.dp),
    )
}

// ==================== SERVER CARD ====================

@Composable
private fun ProviderServerCard(server: ProviderServer, onCopy: () -> Unit) {
    val langColor = when (server.language.lowercase()) {
        "latino", "español latino" -> Color(0xFFE50914)
        "subtitulado" -> Color(0xFF2196F3)
        "castellano" -> Color(0xFFFF9800)
        else -> Color(0xFF607D8B)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onCopy() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Lenguaje badge
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = langColor,
            ) {
                Text(
                    server.language.uppercase(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.serverName, color = Color.White,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    server.domain, color = Color(0xFF78909C),
                    fontSize = 11.sp,
                )
            }
            Icon(Icons.Default.ContentCopy, "Copiar URL", tint = Color(0xFF546E7A), modifier = Modifier.size(18.dp))
        }
        // URL completa (mono, pequeño)
        Text(
            server.embedUrl, color = Color(0xFF455A64), fontSize = 10.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}