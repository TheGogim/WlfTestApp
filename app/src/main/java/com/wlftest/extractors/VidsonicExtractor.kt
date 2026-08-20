package com.wlftest.extractors

import com.wlftest.ui.LogCollector
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Copiado de WlfMovie.
 * Lógica 100% original. Solo cambiado HTTP layer:
 * Original: Retrofit + JsoupConverterFactory
 * Adaptado: HttpHelper + Jsoup.parse()
 */
class VidsonicExtractor : Extractor() {
    override val name = "Vidsonic"
    override val mainUrl = "https://vidsonic.net"

    override suspend fun extract(link: String): Video {
        val html = HttpHelper.httpGet(link)

        val encodedMatch = Regex("'([a-fA-F0-9|]{60,})'").find(html)
            ?: throw Exception("No se encontro el string codificado en Vidsonic")

        val cleaned = encodedMatch.groupValues[1].replace("|", "")

        val asciiBuilder = StringBuilder()
        for (i in cleaned.indices step 2) {
            val hexPair = cleaned.substring(i, i + 2)
            asciiBuilder.append(hexPair.toInt(16).toChar())
        }

        val sourceUrl = asciiBuilder.toString().reversed()

        LogCollector.log("SUCCESS", "[Vidsonic] $sourceUrl")

        return Video(
            source = sourceUrl,
            headers = mapOf("Referer" to mainUrl, "Origin" to mainUrl)
        )
    }
}
