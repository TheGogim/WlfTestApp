package com.wlftest.model

enum class ShowType { MOVIE, TV }

data class ShowItem(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: String?,
    val rating: Float,
    val type: ShowType,
    val overview: String?,
)

data class ShowDetail(
    val id: Int,
    val title: String,
    val originalTitle: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: String?,
    val rating: Float,
    val type: ShowType,
    val overview: String,
    val genres: List<String>,
    val runtime: Int?,
    val numberOfSeasons: Int?,
    val cast: List<CastMember>,
)

data class CastMember(
    val name: String,
    val character: String,
    val photoUrl: String?,
)

// --- Nuevos: Episodios y Servidores ---

data class EpisodeItem(
    val episodeNumber: Int,
    val name: String,
    val overview: String?,
    val posterUrl: String?,
    val airDate: String?,
)

data class GnulaServer(
    val language: String,
    val serverName: String,
    val embedUrl: String,
    val domain: String,
)

/**
 * Servidor genérico para cualquier provider.
 * embedUrl puede ser una URL directa o un path del tipo /player/{b64}
 */
data class ProviderServer(
    val providerName: String,
    val language: String,
    val serverName: String,
    val embedUrl: String,
    val domain: String,
)