package com.wlftest.extractors

import android.util.Base64
import com.wlftest.ui.LogCollector

/**
 * Copiado de WlfMovie.
 * Lógica 100% original. Solo cambiado HTTP layer:
 * Original: Retrofit + ScalarsConverterFactory
 * Adaptado: HttpHelper
 */
class VidGuardExtractor : Extractor() {
    override val name = "VidGuard"
    override val mainUrl = "https://vidguard.to"
    override val aliasUrls = listOf(
        "https://vembed.net",
        "https://bembed.cc",
        "https://vgfplay.com",
        "https://listeamed.net",
        "https://vidguard.to"
    )

    override suspend fun extract(link: String): Video {
        val pageHtml = try {
            HttpHelper.httpGet(link)
        } catch (_: Exception) {
            HttpHelper.httpGet("https:$link")
        }

        val scriptData = pageHtml
            .substringAfter("eval(function(p,a,c,k,e,d)")
            .substringBefore("</script>")
            .let { "eval(function(p,a,c,k,e,d)$it" }

        if (!scriptData.startsWith("eval")) {
            throw Exception("No se encontro el script eval en VidGuard")
        }

        val unpackedScript = JsUnpacker(scriptData).unpack()
            ?: throw Exception("No se pudo desempacar el script")

        val urlEncoded = unpackedScript
            .substringAfter("window.svg={\"stream\":\"")
            .substringBefore("\",\"hash")

        val finalUrl = sigDecode(urlEncoded)

        return Video(source = finalUrl, headers = mapOf("Referer" to mainUrl))
    }

    private fun sigDecode(url: String): String {
        val sig = url.split("sig=")[1].split("&")[0]
        val decodedSig = sig.chunked(2)
            .joinToString("") { (Integer.parseInt(it, 16) xor 2).toChar().toString() }
            .let {
                val padding = when (it.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                String(Base64.decode(it + padding, Base64.DEFAULT))
            }
            .dropLast(5)
            .reversed()
            .toCharArray()
            .apply {
                for (i in indices step 2) {
                    if (i + 1 < size) {
                        this[i] = this[i + 1].also { this[i + 1] = this[i] }
                    }
                }
            }
            .concatToString()
            .dropLast(5)
        return url.replace(sig, decodedSig)
    }
}
