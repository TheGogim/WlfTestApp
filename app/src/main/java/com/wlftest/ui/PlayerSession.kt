package com.wlftest.ui

import com.wlftest.extractors.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Un servidor que ya tiene la URL de video resuelta (no solo el embed URL).
 * Se agrega a la lista de "Servidores disponibles" del player.
 */
data class ResolvedServer(
    val language: String,
    val serverName: String,
    val domain: String,
    val embedUrl: String,
    val video: Video,
)

/**
 * Estado del reproductor: qué se está reproduciendo ahora + alternativas.
 *
 * El video activo puede cambiar cuando el usuario selecciona otro servidor
 * del bottom sheet (no se cambia solo cuando llega un nuevo servidor resuelto
 * — solo se agrega a availableServers).
 */
data class PlaybackState(
    val title: String,
    val subtitle: String = "",
    val activeVideo: Video,
    val activeServerName: String,
    val availableServers: List<ResolvedServer> = emptyList(),
)

/**
 * Singleton holder para pasar el PlaybackState del DetailScreen al PlayerScreen.
 *
 * El DetailViewModel hace playNow() → lanza extracciones paralelas → cuando
 * el primer servidor resuelto llega, llama start() con ese video + lista vacía
 * de availableServers → navega a "player" → PlayerScreen lee state.value.
 *
 * Mientras tanto, las otras extracciones siguen corriendo en background.
 * Cuando llegan, DetailViewModel llama updateAvailableServers() → PlayerScreen
 * recomponga y muestra más opciones en el bottom sheet.
 *
 * Cuando el usuario cambia de servidor en el sheet, se llama switchTo() que
 * actualiza activeVideo + activeServerName — el PlayerScreen reacciona con
 * LaunchedEffect(activeVideo) cambiando la fuente del ExoPlayer.
 */
object PlayerSessionHolder {
    private val _state = MutableStateFlow<PlaybackState?>(null)
    val state: StateFlow<PlaybackState?> = _state.asStateFlow()

    fun start(initial: PlaybackState) {
        _state.value = initial
    }

    fun updateAvailableServers(servers: List<ResolvedServer>) {
        _state.value?.let { current ->
            _state.value = current.copy(availableServers = servers)
        }
    }

    fun switchTo(server: ResolvedServer) {
        _state.value?.let { current ->
            _state.value = current.copy(
                activeVideo = server.video,
                activeServerName = server.serverName,
            )
        }
    }

    fun clear() {
        _state.value = null
    }
}
