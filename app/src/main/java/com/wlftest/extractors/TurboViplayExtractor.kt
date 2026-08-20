package com.wlftest.extractors

import com.wlftest.ui.LogCollector
import org.jsoup.Jsoup

/**
 * NUEVO — No existe en WlfMovie.
 * Creado siguiendo el plan: extrae var urlPlay del HTML.
 *
 * Estructura conocida del embed:
 *   var videoID = '...';
 *   var urlPlay = 'https://cdn2.turboviplay.com/data3/.../....m3u8';
 *   var urlSub = 'https://sub.turboviplay.to/sub/.../....json';
 */
class TurboViplayExtractor : Extractor() {
    override val name = "TurboViplay"
    override val mainUrl = "https://turboviplay.com"
    override val aliasUrls = listOf("https://cdn2.turboviplay.com", "https://emturbovid.com", "https://cdn1.turboviplay.com")

    override suspend fun extract(link: String): Video {
        val html = HttpHelper.httpGet(link)

        // Raw string: \s en raw string es literal, usar \s para regex
        val urlPlay = Regex("""var\s+urlPlay\s*=\s*["'](https?://[^"']+)"']""").find(html)?.groupValues?.get(1)
            ?: throw Exception("No se encontro urlPlay en TurboViplay")

        LogCollector.log("SUCCESS", "[TurboViplay] $urlPlay")

        // Subtitulos (opcional)
        val subtitles = mutableListOf<Video.Subtitle>()
        val urlSub = Regex("""var\s+urlSub\s*=\s*["'](https?://[^"']+)"']""").find(html)?.groupValues?.get(1)
        if (urlSub != null) {
            try {
                val subJson = HttpHelper.httpGet(urlSub)
                val arr = org.json.JSONArray(subJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    subtitles.add(Video.Subtitle(
                        label = obj.optString("language", "es"),
                        file = obj.getString("url")
                    ))
                }
            } catch (e: Exception) {
                LogCollector.log("WARN", "No se pudieron cargar subs: ${e.message}")
            }
        }

        return Video(
            source = urlPlay,
            type = "application/x-mpegURL",
            headers = mapOf("Referer" to link),
            subtitles = subtitles
        )
    }
}
