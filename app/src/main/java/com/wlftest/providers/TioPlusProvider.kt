package com.wlftest.providers

import com.wlftest.model.ProviderServer
import com.wlftest.model.ShowType
import com.wlftest.ui.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
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
            log("ERROR", "HTTP ${response.code}: ${response.message}")
            throw Exception("HTTP ${response.code}: ${response.message}")
        }
        log("RESPONSE", "${response.code} OK (${body.length} chars)")
        body
    }

    private fun normalize(s: String): String {
        val noAccents = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noAccents.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isMatch(found: String, target: String): Boolean {
        val nFound = normalize(found)
        val nTarget = normalize(target)
        if (nFound == nTarget) return true
        if (nFound.contains(nTarget) || nTarget.contains(nFound)) return true
        val foundWords = nFound.split(" ").filter { it.length > 2 }.toSet()
        val targetWords = nTarget.split(" ").filter { it.length > 2 }.toSet()
        if (foundWords.isEmpty() || targetWords.isEmpty()) return false
        return foundWords.intersect(targetWords).size.toFloat() / targetWords.size >= 0.5f
    }

    // --- Search result parsing ---

    private data class SearchResult(
        val title: String,
        val slug: String,
        val type: String, // "pelicula" o "serie"
    )

    /**
     * Parsea los resultados HTML de /api/search/{query}
     * Formato: <article>...<a href="https://tioplus.app/pelicula/slug">...<span class="typeItem movie">Pelicúla</span>...<h2>Title (Year)</h2>...</article>
     */
    private fun parseSearchResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val articleRegex = Regex("""<article class='item liste relative'>\s*<a class='itemA' href="([^"]+)">(\s|\S)*?<h2>([^<]+)</h2>(\s|\S)*?<span class="typeItem(movie)?">([^<]*)</span>""")

        // Más simple: extraer hrefs y títulos por separado
        val hrefRegex = Regex("""<a class='itemA' href="(https://tioplus\.app/(?:pelicula|serie)/[^"]+)"""")
        val titleRegex = Regex("""<h2>([^<]+)</h2>""")
        val typeRegex = Regex("""<span class="typeItem(movie)?">([^<]+)</span>""")

        val hrefs = hrefRegex.findAll(html).map { it.groupValues[1] }.toList()
        val titles = titleRegex.findAll(html).map { it.groupValues[1] }.toList()
        val types = typeRegex.findAll(html).map {
            val isMovie = it.groupValues[1].isNotEmpty()
            if (isMovie) "pelicula" else "serie"
        }.toList()

        val count = minOf(hrefs.size, titles.size, types.size)
        for (i in 0 until count) {
            val slug = hrefs[i].trimEnd('/').substringAfterLast('/')
            results.add(SearchResult(
                title = titles[i].trim(),
                slug = slug,
                type = types[i],
            ))
        }
        return results
    }

    /**
     * Extrae los servidores de una página de película o episodio.
     * Busca <li data-server="base64">  <span>Nombre - Opción N</span>...
     * y también el <button> que indica el idioma.
     */
    private fun parseServers(html: String): List<ProviderServer> {
        val servers = mutableListOf<ProviderServer>()

        // Extraer bloques de idioma: cada <div> dentro de .bg-tabs contiene un button + ul.subselect
        // El button tiene el nombre del idioma (ej: "Español Latino")
        // Los <li data-server="..."> tienen los servidores

        // Regex para los bloques: button con texto de idioma seguido de ul.subselect con li data-server
        val blockRegex = Regex(
            """<button class='active button'>[^<]*<img[^>]*>\s*([^<]+)""" +
            """[\\s\\S]*?<ul class='subselect'>([\\s\\S]*?)</ul>"""
        )

        val blocks = blockRegex.findAll(html).toList()

        // Si no funciona el block regex, intentar extraer directamente
        val serverRegex = Regex("""data-server="([^"]+)"[\\s\\S]*?<span>([^<]+)</span>""")

        // Primero intentar por bloques de idioma
        if (blocks.isNotEmpty()) {
            for (block in blocks) {
                val language = block.groupValues[1].trim()
                val ulContent = block.groupValues[2]
                val liRegex = Regex("""data-server="([^"]+)"[\\s\\S]*?<span>([^<]+)</span>""")
                val matches = liRegex.findAll(ulContent).toList()
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
            }
        } else {
            // Fallback: extraer todos los data-server directamente
            val matches = serverRegex.findAll(html).toList()
            // Intentar obtener el idioma del primer button
            val langMatch = Regex("""<button class='active button'>[^<]*<img[^>]*>\s*([^<]+)""").find(html)
            val language = langMatch?.groupValues?.get(1)?.trim() ?: "Latino"
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
        }

        return servers
    }

    // --- Función principal ---

    suspend fun searchServers(
        title: String,
        type: ShowType,
        seasonNum: Int? = null,
        episodeNum: Int? = null,
    ): List<ProviderServer> {
        log("INFO", "═══ TioPlus — Iniciando búsqueda ═══")
        log("INFO", "Título: \"$title\" | Tipo: ${if (type == ShowType.TV) "Serie" else "Película"}" +
                (if (seasonNum != null) " | Temporada $seasonNum, Episodio $episodeNum" else ""))

        // 1. Buscar en la API
        val query = URLEncoder.encode(title, "UTF-8")
        val searchUrl = "$BASE_URL/api/search/$query"
        val searchHtml = try {
            httpGet(searchUrl)
        } catch (e: Exception) {
            log("ERROR", "Error en búsqueda: ${e.message}")
            return emptyList()
        }

        if (searchHtml.contains("No hay resultados")) {
            log("ERROR", "Sin resultados en TioPlus para \"$title\"")
            return emptyList()
        }

        val results = parseSearchResults(searchHtml)
        log("INFO", "${results.size} resultado(s) en TioPlus")
        results.forEach {
            log("INFO", "  → ${it.title} (${it.type})")
        }

        // 2. Fuzzy match
        val expectedType = if (type == ShowType.TV) "serie" else "pelicula"
        val match = results.firstOrNull { result ->
            result.type == expectedType && isMatch(result.title, title)
        }

        if (match == null) {
            log("ERROR", "Sin coincidencia para \"$title\" como $expectedType")
            return emptyList()
        }

        log("MATCH", "\"${match.title}\" (${match.slug})")

        // 3. Construir URL de la página
        val pageUrl = if (type == ShowType.TV && seasonNum != null && episodeNum != null) {
            val url = "$BASE_URL/serie/${match.slug}/season/$seasonNum/episode/$episodeNum"
            log("INFO", "URL episodio: $url")
            url
        } else if (type == ShowType.TV) {
            // Sin episodio seleccionado, no podemos buscar
            log("ERROR", "Se necesita temporada y episodio para series")
            return emptyList()
        } else {
            val url = "$BASE_URL/pelicula/${match.slug}"
            log("INFO", "URL página: $url")
            url
        }

        // 4. Obtener página y extraer servidores
        val pageHtml = try {
            httpGet(pageUrl)
        } catch (e: Exception) {
            log("ERROR", "Error cargando página: ${e.message}")
            return emptyList()
        }

        val servers = parseServers(pageHtml)

        if (servers.isEmpty()) {
            log("ERROR", "No se encontraron servidores en la página")
            return emptyList()
        }

        log("SUCCESS", "${servers.size} servidor(es) encontrado(s)")
        servers.forEach {
            log("INFO", "    → [${it.language}] ${it.serverName}")
        }
        log("SUCCESS", "═══ ${servers.size} servidores listos ═══")

        return servers
    }
}
