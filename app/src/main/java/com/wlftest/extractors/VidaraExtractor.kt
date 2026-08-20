package com.wlftest.extractors

import com.google.gson.JsonParser
import com.wlftest.ui.LogCollector
import java.net.URL

/**
 * Copiado de WlfMovie.
 * Alias agregado: vidaraa.cc
 *
 * Original: Retrofit + Gson + DnsResolver
 * Adaptado: HttpHelper + Gson
 */
class VidaraExtractor : Extractor() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.to"
    override val aliasUrls = listOf(
        "https://vidara.so",
        // ALIAS NUEVO para Gnula:
        "https://vidaraa.cc",
    )

    override suspend fun extract(link: String): Video {
        val fileCode = URL(link).path.split("/").last { it.isNotEmpty() }
        val baseUrl = URL(link).protocol + "://" + URL(link).host

        val jsonBody = HttpHelper.httpPost(
            url = "$baseUrl/api/stream",
            headers = mapOf("Content-Type" to "application/json"),
            body = "{\"filecode\":\"$fileCode\",\"device\":\"web\"}"
        )

        val json = JsonParser.parseString(jsonBody).asJsonObject
        val streamingUrl = json.get("streaming_url")?.asString
            ?: throw Exception("streaming_url not found in Vidara response")

        LogCollector.log("SUCCESS", "[Vidara] ${streamingUrl.take(80)}...")
        return Video(source = streamingUrl)
    }
}