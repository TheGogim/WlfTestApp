package com.wlftest.extractors

import com.wlftest.ui.LogCollector

/**
 * Extractor para savefiles.com y mirrors.
 *
 * Flujo:
 * 1. La pagina /e/CODE muestra un form POST a /dl
 * 2. POST a /dl con op=embed, file_code, auto=1 retorna la pagina del player
 * 3. La respuesta contiene JWPlayer con la URL HLS del video
 * 4. Se extrae con regex el campo file: del setup de JWPlayer
 *
 * MEJORAS 2025-08:
 *  - Detectar "File was locked by administrator" y dar error claro (no es bug nuestro).
 *  - Detectar bloqueo de Cloudflare.
 *  - Hacer parsing más robusto del JWPlayer setup (con y sin comillas, con sources array).
 *  - Soportar también la forma "sources: [{file: '...'}]".
 */
class SavefilesExtractor : Extractor() {
    override val name = "Savefiles"
    override val mainUrl = "https://savefiles.com"
    override val aliasUrls = listOf(
        "https://savefiles.com",
        "https://www.savefiles.com",
    )

    override suspend fun extract(link: String): Video {
        // Extraer el file_code de la URL
        val code = extractCode(link)
            ?: throw Exception("No se pudo extraer file_code de: $link")

        val mainLink = "https://savefiles.com"
        LogCollector.log("EXTRACTOR", "[Savefiles] POST /dl con code=$code")

        // 1. GET /e/CODE para obtener cookies de sesión
        try {
            HttpHelper.httpGet(link)
        } catch (_: Exception) {
            // No crítico — seguimos con el POST
        }

        // 2. POST al endpoint /dl
        val formData = "op=embed&file_code=$code&auto=1&referer="
        val response = HttpHelper.httpPost(
            "$mainLink/dl",
            mapOf(
                "Referer" to link,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin" to mainLink
            ),
            formData
        )

        LogCollector.log("RESPONSE", "[Savefiles] /dl response: ${response.length} chars")

        // 3. Detectar casos de error conocidos
        if (response.contains("File was locked by administrator") ||
            response.contains("over_player_msg")) {
            LogCollector.log("WARN", "[Savefiles] El archivo fue bloqueado por el administrador")
            throw Exception("Savefiles: el archivo '$code' fue bloqueado por el administrador (no es un bug del extractor)")
        }
        if (response.contains("File was deleted") || response.contains("File not found")) {
            throw Exception("Savefiles: el archivo '$code' fue eliminado o no existe")
        }
        if (response.contains("cf-browser-verification") || response.contains("cf-challenge")) {
            throw Exception("Savefiles: bloqueado por Cloudflare (intenta de nuevo más tarde)")
        }

        // 4. Buscar URL de video en la respuesta JWPlayer
        // Formato 1: file:"https://...master.m3u8?..." o file: "https://..."
        val videoUrl = findVideoUrl(response)

        if (videoUrl.isNullOrEmpty()) {
            // Log fragmento para diagnóstico
            LogCollector.log("DEBUG", "[Savefiles] No se encontró URL de video. Response snippet: ${response.take(500)}")
            throw Exception("Savefiles: no se encontró URL de video en /dl (posiblemente bloqueado o HTML cambió)")
        }

        LogCollector.log("SUCCESS", "[Savefiles] ${videoUrl.take(100)}...")
        return Video(
            source = videoUrl,
            type = if (videoUrl.contains(".m3u8")) "application/x-mpegURL" else "video/mp4",
            headers = mapOf(
                "Referer" to mainLink,
                "User-Agent" to HttpHelper.UA
            )
        )
    }

    /**
     * Busca la URL del video en el HTML del player de Savefiles.
     * Soporta múltiples formatos:
     *  - file: "https://...m3u8"  (con comillas dobles)
     *  - file: 'https://...m3u8'  (con comillas simples)
     *  - file: https://...m3u8    (sin comillas)
     *  - sources: [{file: "https://...m3u8", ...}]
     *  - Cualquier URL .m3u8 o .mp4 directamente en el HTML
     */
    private fun findVideoUrl(html: String): String? {
        // Formato 1: file:"..." o file: "..." o file:'...' o file: '...'
        val fileRegexes = listOf(
            Regex("""file\s*:\s*["'](https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)["']"""),
            Regex("""file\s*:\s*(https?://[^\s,;}'"]+\.(?:m3u8|mp4)[^\s,;}'"]*)"""),
        )
        for (r in fileRegexes) {
            val match = r.find(html)?.groupValues?.getOrNull(1)
            if (!match.isNullOrEmpty()) return match
        }

        // Formato 2: sources: [{file: "..."}]
        val sourcesArrayRegex = Regex(
            """sources\s*:\s*\[(?:[^\]]*\{[^}]*file\s*:\s*["'](https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)["'][^}]*\})"""
        )
        sourcesArrayRegex.find(html)?.groupValues?.getOrNull(1)?.let { return it }

        // Formato 3: Cualquier URL directa .m3u8 o .mp4
        val directRegex = Regex("""(https?://[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*)""")
        return directRegex.find(html)?.groupValues?.getOrNull(1)
    }

    private fun extractCode(link: String): String? {
        // https://savefiles.com/e/u5pj9zvgc4xw -> u5pj9zvgc4xw
        // https://savefiles.com/e/u5pj9zvgc4xw.html -> u5pj9zvgc4xw
        val path = link.substringAfterLast("/").substringBefore("?")
            .removeSuffix(".html")
        return if (path.length >= 8 && path.all { it.isLetterOrDigit() }) path else null
    }
}
