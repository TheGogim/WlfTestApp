package com.wlftest.extractors

import java.io.Serializable

/**
 * Modelo de video extraido.
 * Simplificado del original WlfMovie para la app de prueba.
 */
data class Video(
    val source: String,
    val subtitles: List<Subtitle> = emptyList(),
    val headers: Map<String, String>? = null,
    val type: String? = null,
) : Serializable {
    data class Subtitle(
        val label: String,
        val file: String,
        var default: Boolean = false,
    ) : Serializable
}
