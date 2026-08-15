package com.wlftest.providers

import android.util.Base64
import com.wlftest.model.GnulaServer
import com.wlftest.model.ShowType
import com.wlftest.ui.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object GnulaProvider {
    const val BASE_URL = "https://ww3.gnulahd.nu"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- TMDB Response Models ---

    @Serializable
    data class SearchResponse(val q: String, val results: List<SearchResult>)

    @Serializable
    data class SearchResult(
        val title: String,
        val url: String,
        val img: String,
        val type: String,
        val year: String,
    )

    @Serializable
    data class PlayerResponse(val p: String)

    @Serializable
    data class PlayerData(val t: String, val langs: List<AudioGroup>)

    @Serializable
    data class AudioGroup(val flag: String, val label: String, val servers: List<ServerEntry>)

    @Serializable
    data class ServerEntry(val title: String, val src: String)

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

    /**
     * Desencripta la respuesta XOR de Gnula.
     * La función JS original es: atob(s) → XOR con key [103,78,55,100] rotativa → JSON.parse
     */
    private fun xorDecrypt(encrypted: String): String {
        val decoded = Base64.decode(encrypted, Base64.DEFAULT)
        val key = byteArrayOf(103, 78, 55, 100) // "gN7d"
        val result = ByteArray(decoded.size) { i ->
            (decoded[i].toInt() xor key[i % 4].toInt()).toByte()
        }
        return String(result, Charsets.UTF_8)
    }

    /**
     * Extrae _gnrdPid y _gnrdTok del HTML de una página de Gnula.
     * Están en <script> inline: var _gnrdPid = 12345; var _gnrdTok = "abc...";
     */
    private fun extractTokens(html: String): Pair<Int, String> {
        // La página usa formato: var _gnrdPid=204535,_gnrdTok="43bd40ba...";
        // Nótese que _gnrdTok NO lleva "var" antes, está después de una coma
        val pidRegex = Regex("""[,_]?\s*_gnrdPid\s*=\s*(\d+)""")
        val tokRegex = Regex("""[,_]?\s*_gnrdTok\s*=\s*"([a-f0-9]+)""")

        val pid = pidRegex.find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw Exception("No se encontró _gnrdPid en la página")
        val tok = tokRegex.find(html)?.groupValues?.get(1)
            ?: throw Exception("No se encontró _gnrdTok en la página")

        return Pair(pid, tok)
    }

    /**
     * Fuzzy match simplificado (misma lógica que la app original).
     * Normaliza tildes, quita símbolos, compara palabras.
     */
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

    // --- Función principal ---

    /**
     * Busca servidores de una película o episodio en Gnula HD.
     *
     * Flujo:
     * 1. Busca el título en la API de búsqueda de Gnula
     * 2. Hace fuzzy match con el resultado
     * 3. Si es serie: construye la URL del episodio ({slug}-{S}x{E:02d}/)
     * 4. Extrae _gnrdPid y _gnrdTok del HTML
     * 5. Llama la API del player
     * 6. Desencripta la respuesta XOR
     * 7. Retorna lista de servidores con embed URLs
     */
    suspend fun searchServers(
        title: String,
        type: ShowType,
        seasonNum: Int? = null,
        episodeNum: Int? = null,
    ): List<GnulaServer> {
        log("INFO", "═══ Gnula HD — Iniciando búsqueda ═══")
        log("INFO", "Título: \"$title\" | Tipo: ${if (type == ShowType.TV) "Serie" else "Película"}" +
                (if (seasonNum != null) " | Temporada $seasonNum, Episodio $episodeNum" else ""))

        // 1. Buscar en la API de Gnula
        val query = URLEncoder.encode(title, "UTF-8")
        val searchUrl = "$BASE_URL/wp-json/gnrd/v1/search?q=$query"
        val searchBody = httpGet(searchUrl)

        val searchResp = try {
            json.decodeFromString<SearchResponse>(searchBody)
        } catch (e: Exception) {
            log("ERROR", "Error parseando respuesta de búsqueda: ${e.message}")
            return emptyList()
        }

        log("INFO", "${searchResp.results.size} resultado(s) en Gnula")
        searchResp.results.forEach {
            log("INFO", "  → ${it.title} (${it.type}, ${it.year})")
        }

        // 2. Fuzzy match
        val expectedType = if (type == ShowType.TV) "Serie" else "Pelicula"
        val match = searchResp.results.firstOrNull { result ->
            result.type.equals(expectedType, ignoreCase = true) && isMatch(result.title, title)
        }

        if (match == null) {
            log("ERROR", "Sin coincidencia para \"$title\" como $expectedType")
            return emptyList()
        }

        log("MATCH", "\"${match.title}\" (${match.url})")

        // 3. Construir URL de la página (película o episodio)
        val pageUrl = if (type == ShowType.TV && seasonNum != null && episodeNum != null) {
            // Extraer slug de la URL de la serie: /ver/la-casa-del-dragon/ → la-casa-del-dragon
            val slug = match.url.trimEnd('/').substringAfterLast('/')
            val epUrl = "$BASE_URL/$slug-${seasonNum}x${String.format("%02d", episodeNum)}/"
            log("INFO", "URL episodio: $epUrl")
            epUrl
        } else {
            log("INFO", "URL página: ${match.url}")
            match.url
        }

        // 4. Obtener página y extraer tokens
        val pageHtml = httpGet(pageUrl)

        val (pid, tok) = try {
            extractTokens(pageHtml)
        } catch (e: Exception) {
            log("ERROR", "${e.message}")
            return emptyList()
        }
        log("PARSE", "_gnrdPid = $pid")
        log("PARSE", "_gnrdTok = ${tok.take(12)}...")

        // 5. Llamar API del player
        val playerUrl = "$BASE_URL/wp-json/gnrd/v1/player?id=$pid&t=$tok"
        val playerBody = httpGet(playerUrl)

        val playerResp = try {
            json.decodeFromString<PlayerResponse>(playerBody)
        } catch (e: Exception) {
            log("ERROR", "Error parseando respuesta del player: ${e.message}")
            return emptyList()
        }

        log("ENCRYPT", "Payload encriptado: ${playerResp.p.take(40)}... (${playerResp.p.length} chars)")

        // 6. Desencriptar
        val decrypted = try {
            xorDecrypt(playerResp.p)
        } catch (e: Exception) {
            log("ERROR", "Error desencriptando: ${e.message}")
            return emptyList()
        }

        log("DECRYPT", "XOR key = [103, 78, 55, 100] (\"gN7d\")")
        log("DECRYPT", "JSON decodificado OK")

        // 7. Parsear servidores — el JSON es un objeto {"t":"...","langs":[...]}
        val playerData = try {
            json.decodeFromString<PlayerData>(decrypted)
        } catch (e: Exception) {
            log("ERROR", "Error parseando respuesta del player: ${e.message}")
            log("PARSE", "Preview del JSON: ${decrypted.take(200)}")
            return emptyList()
        }

        log("INFO", "Título Gnula: \"${playerData.t}\"")
        log("SUCCESS", "${playerData.langs.size} grupo(s) de audio encontrado(s)")

        val servers = mutableListOf<GnulaServer>()
        for (group in playerData.langs) {
            log("INFO", "  🎬 ${group.label}: ${group.servers.size} servidor(es)")
            for (server in group.servers) {
                val domain = try {
                    java.net.URL(server.src).host
                } catch (e: Exception) {
                    server.src.substringBefore("/")
                }
                servers.add(GnulaServer(
                    language = group.label,
                    serverName = server.title,
                    embedUrl = server.src,
                    domain = domain,
                ))
                log("INFO", "    → ${server.title}: $domain")
            }
        }

        log("SUCCESS", "═══ ${servers.size} servidores listos ═══")
        return servers
    }
}
