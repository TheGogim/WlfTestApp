package com.wlftest.providers

import com.wlftest.model.ProviderServer
import com.wlftest.model.ShowType
import com.wlftest.ui.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object TioPlusProvider {
    const val BASE_URL = "https://tioplus.app"
    const val PROVIDER_NAME = "TioPlus"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // --- Helpers ---

    private fun log(tag: String, msg: String) = LogCollector.log(tag, msg)

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        log("REQUEST", "GET $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) {
            log("RESPONSE", "HTTP ${response.code}")
            throw Exception("HTTP ${response.code}")
        }
        log("RESPONSE", "${response.code} OK (${body.length} chars)")
        body
    }

    // --- Slug generation ---

    /**
     * Slug limpio: quita tildes, mayusculas, TODO excepto a-z 0-9 y espacios.
     * "Vengadores: Endgame" -> "vengadores-endgame"
     * "La casa del dragon" -> "la-casa-del-dragon"
     */
    private fun toSlugClean(title: String): String {
        return java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
    }

    /**
     * Slug manteniendo puntos: quita tildes y :?!;"' etc, pero guarda los puntos.
     * "S.W.A.T." -> "s.w.a.t."
     * "Dr. Strange" -> "dr.-strange"
     */
    private fun toSlugWithDots(title: String): String {
        return java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9. ]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
    }

    /**
     * Genera variaciones de slug en orden de prioridad:
     * 1. Slug limpio del titulo principal
     * 2. Slug con puntos del titulo principal
     * 3. Slug limpio del titulo alternativo (otro idioma)
     * 4. Slug con puntos del titulo alternativo
     */
    private fun generateSlugVariations(
        title: String,
        alternateTitle: String?,
    ): List<Pair<String, String>> {
        val variations = mutableListOf<Pair<String, String>>()

        // 1. Slug limpio del titulo principal
        val clean1 = toSlugClean(title)
        if (clean1.isNotBlank()) {
            variations.add(clean1 to "limpio ($title)")
        }

        // 2. Slug con puntos del titulo principal
        val dots1 = toSlugWithDots(title)
        if (dots1.isNotBlank() && dots1 != clean1) {
            variations.add(dots1 to "con puntos ($title)")
        }

        // 3 y 4. Titulo alternativo (otro idioma)
        if (!alternateTitle.isNullOrBlank() && alternateTitle != title) {
            val clean2 = toSlugClean(alternateTitle)
            if (clean2.isNotBlank() && clean2 != clean1) {
                variations.add(clean2 to "limpio alt ($alternateTitle)")
            }

            val dots2 = toSlugWithDots(alternateTitle)
            if (dots2.isNotBlank() && dots2 != dots1 && dots2 != clean2) {
                variations.add(dots2 to "con puntos alt ($alternateTitle)")
            }
        }

        return variations
    }

    // --- Server parsing ---

    private fun parseServers(html: String): List<ProviderServer> {
        val servers = mutableListOf<ProviderServer>()

        val langRegex = Regex("""<button class='active button'>[^<]*<img[^>]*>([^<]+)""")
        val language = langRegex.find(html)?.groupValues?.get(1)?.trim() ?: "Latino"
        log("PARSE", "Idioma detectado: \"$language\"")

        val serverRegex = Regex("""data-server="([^"]+)"[^<]*<span>([^<]+)</span>""")
        val matches = serverRegex.findAll(html).toList()

        log("PARSE", "${matches.size} servidor(es) en HTML")

        for (m in matches) {
            val b64 = m.groupValues[1]
            val serverName = m.groupValues[2].trim()
            val embedUrl = "$BASE_URL/player/$b64"
            servers.add(ProviderServer(
                providerName = PROVIDER_NAME,
                language = language,
                serverName = serverName,
                embedUrl = embedUrl,
                domain = "tioplus.app/player",
            ))
        }

        return servers
    }

    // --- Funcion principal ---

    /**
     * Busca servidores construyendo directamente el slug desde el titulo de TMDB.
     * No usa /api/search/ porque es muy poco confiable con titulos complejos.
     *
     * Intenta multiple variaciones de slug:
     * - Limpios (sin tildes, puntos, dos puntos, etc.)
     * - Con puntos (si el titulo los tiene)
     * - En titulo alternativo (otro idioma) como ultimo recurso
     *
     * @param title Titulo principal (el que da TMDB en el idioma solicitado)
     * @param type MOVIE o TV
     * @param alternateTitle Titulo en el otro idioma (originalTitle de TMDB)
     */
    suspend fun searchServers(
        title: String,
        type: ShowType,
        alternateTitle: String? = null,
        seasonNum: Int? = null,
        episodeNum: Int? = null,
    ): List<ProviderServer> {
        log("INFO", "═══ TioPlus — Busqueda por slug directo ═══")
        log("INFO", "Titulo: \"$title\" | Tipo: ${if (type == ShowType.TV) "Serie" else "Pelicula"}" +
                (if (seasonNum != null) " | Temporada $seasonNum, Episodio $episodeNum" else ""))
        if (!alternateTitle.isNullOrBlank() && alternateTitle != title) {
            log("INFO", "Titulo alternativo: \"$alternateTitle\"")
        }

        val path = if (type == ShowType.TV) "serie" else "pelicula"

        val variations = generateSlugVariations(title, alternateTitle)
        log("INFO", "Variaciones de slug: ${variations.size}")
        variations.forEachIndexed { i, (slug, desc) ->
            log("INFO", "  Slug ${i + 1}: \"$slug\" ($desc)")
        }

        for ((slug, desc) in variations) {
            val url = if (type == ShowType.TV && seasonNum != null && episodeNum != null) {
                "$BASE_URL/$path/$slug/season/$seasonNum/episode/$episodeNum"
            } else if (type == ShowType.TV) {
                log("ERROR", "Se necesita temporada y episodio para series")
                return emptyList()
            } else {
                "$BASE_URL/$path/$slug"
            }

            try {
                val html = httpGet(url)

                if (!html.contains("data-server")) {
                    log("INFO", "Sin servidores con slug \"$slug\", probando siguiente...")
                    continue
                }

                log("MATCH", "Slug encontrado: \"$slug\" ($desc)")

                val servers = parseServers(html)
                if (servers.isEmpty()) {
                    log("ERROR", "HTML tiene data-server pero no se parsearon servidores")
                    continue
                }

                log("SUCCESS", "═══ ${servers.size} servidores listos ═══")
                servers.forEach {
                    log("INFO", "    → [${it.language}] ${it.serverName}")
                }

                return servers
            } catch (e: Exception) {
                log("INFO", "Slug \"$slug\" fallo: ${e.message}")
                continue
            }
        }

        log("ERROR", "Ningun slug funciono para \"$title\"")
        return emptyList()
    }
}
