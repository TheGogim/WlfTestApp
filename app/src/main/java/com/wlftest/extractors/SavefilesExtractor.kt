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
        
        // POST al endpoint /dl
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
        
        // Buscar URL de video en la respuesta JWPlayer
        // Formato tipico: file:"https://...master.m3u8?..." o file: "https://..."
        val videoUrl = Regex(
            """file\s*:\s*["'](https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)["']"""
        ).find(response)?.groupValues?.get(1)
        
        if (videoUrl.isNullOrEmpty()) {
            // Loguear fragmento para debug
            LogCollector.log("DEBUG", "[Savefiles] No file: URL encontrada. Response snippet: ${response.take(500)}")
            throw Exception("No se encontro URL de video en savefiles")
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
    
    private fun extractCode(link: String): String? {
        // https://savefiles.com/e/u5pj9zvgc4xw -> u5pj9zvgc4xw
        // https://savefiles.com/e/u5pj9zvgc4xw.html -> u5pj9zvgc4xw
        val path = link.substringAfterLast("/").substringBefore("?")
            .removeSuffix(".html")
        return if (path.length >= 8 && path.all { it.isLetterOrDigit() }) path else null
    }
}
