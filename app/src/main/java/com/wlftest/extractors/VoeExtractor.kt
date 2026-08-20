package com.wlftest.extractors

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wlftest.ui.LogCollector
import org.jsoup.Jsoup

/**
 * VOE extractor.
 * 2025-08: VOE ahora tiene captcha ALTCHA (proof-of-work, no visual).
 * El captcha se resuelve automaticamente via JS.
 * Solucion: usar WebView para que ALTCHA resuelva, esperar redirect,
 * y extraer el JSON de la pagina resultante.
 */
class VoeExtractor : Extractor() {
    override val name = "VOE"
    override val mainUrl = "https://voe.sx"
    override val needsWebView = true
    override val aliasUrls = listOf(
        "https://jilliandescribecompany.com",
        "https://mikaylaarealike.com",
        "https://christopheruntilpoint.com",
        "https://walterprettytheir.com",
        "https://crystaltreatmenteast.com",
        "https://lauradaydo.com",
        "https://lancewhosedifficult.com",
        "https://dianaavoidthey.com",
        "https://jefferycontrolmodel.com",
        "https://charlestoughrace.com",
        "https://richardquestionbuilding.com",
        "https://jessicayeahcatch.com",
        "https://juliewomanwish.com"
    )

    override suspend fun extractWithWebView(link: String, context: Context): Video {
        LogCollector.log("WEBVIEW", "[VOE] Cargando via WebView (ALTCHA captcha)...")

        val extractJs = """
            (function() {
                try {
                    // Metodo 1: Buscar script type=application/json
                    var scripts = document.querySelectorAll('script[type="application/json"]');
                    for (var i = 0; i < scripts.length; i++) {
                        var text = scripts[i].textContent || '';
                        if (text.length > 100 && (text.indexOf('source') !== -1 || text.indexOf('mp4') !== -1 || text.indexOf('m3u8') !== -1)) {
                            return 'ENCODED:' + text;
                        }
                    }

                    // Metodo 2: Buscar en todos los scripts
                    scripts = document.querySelectorAll('script');
                    for (var i = 0; i < scripts.length; i++) {
                        var text = scripts[i].textContent || '';
                        if (text.length > 200 && text.indexOf('source') !== -1 && (text.indexOf('mp4') !== -1 || text.indexOf('m3u8') !== -1)) {
                            return 'ENCODED:' + text;
                        }
                    }

                    // Metodo 3: Si todavia estamos en la pagina de captcha, reportar
                    if (document.title && document.title.indexOf('human') !== -1) {
                        return 'CAPTCHA_STILL_LOADING';
                    }

                    // Metodo 4: Buscar URL de video directa en el HTML
                    var html = document.documentElement.innerHTML;
                    var m3u8Match = html.match(/https:\/\/[^"'<>\s]+\.m3u8[^"'<>\s]*/);
                    if (m3u8Match) return 'DIRECT:' + m3u8Match[0];

                    var mp4Match = html.match(/https:\/\/[^"'<>\s]+\.mp4[^"'<>\s]*/);
                    if (mp4Match) return 'DIRECT:' + mp4Match[0];

                    // Metodo 5: Buscar en window.__INITIAL_STATE__ u otras variables globales
                    if (window.__INITIAL_STATE__) {
                        var stateStr = JSON.stringify(window.__INITIAL_STATE__);
                        if (stateStr.indexOf('mp4') !== -1 || stateStr.indexOf('m3u8') !== -1) {
                            return 'STATE:' + stateStr;
                        }
                    }

                    return '';
                } catch(e) {
                    return 'ERROR:' + e.message;
                }
            })()
        """.trimIndent()

        // Cargar con espera larga para que ALTCHA resuelva (~5s) + redirect (~3s)
        val result = WebViewHelper.evaluate(
            context = context,
            url = link,
            js = extractJs,
            waitForMs = 6000,
            extraJsWaitMs = 4000
        )

        LogCollector.log("WEBVIEW", "[VOE] Resultado: ${result.take(150)}")

        if (result.isEmpty() || result == "null") {
            throw Exception("No se pudo extraer video de VOE via WebView")
        }

        if (result.startsWith("ERROR:")) {
            throw Exception(result)
        }

        if (result.startsWith("DIRECT:")) {
            val videoUrl = result.removePrefix("DIRECT:")
            LogCollector.log("SUCCESS", "[VOE] URL directa: ${videoUrl.take(80)}...")
            return Video(source = videoUrl, type = if (videoUrl.contains(".m3u8")) "application/x-mpegURL" else "video/mp4")
        }

        if (result.startsWith("CAPTCHA_STILL_LOADING")) {
            LogCollector.log("WARN", "[VOE] ALTCHA todavia cargando, reintentando...")
            val retryResult = WebViewHelper.evaluate(
                context = context,
                url = link,
                js = extractJs,
                waitForMs = 8000,
                extraJsWaitMs = 5000
            )
            if (retryResult.startsWith("ENCODED:") || retryResult.startsWith("DIRECT:") || retryResult.startsWith("STATE:")) {
                return parseResult(retryResult)
            }
            throw Exception("VOE ALTCHA no se resolvio")
        }

        return parseResult(result)
    }

    private fun parseResult(result: String): Video {
        val videoUrl: String
        val subtitles = mutableListOf<Video.Subtitle>()

        if (result.startsWith("ENCODED:")) {
            val encodedString = result.removePrefix("ENCODED:")
            val decryptedContent: JsonObject = try {
                DecryptHelper.decrypt(encodedString)
            } catch (_: Exception) {
                try {
                    JsonParser.parseString(encodedString).asJsonObject
                } catch (_: Exception) {
                    throw Exception("No se pudo decodificar el JSON de VOE")
                }
            }

            videoUrl = decryptedContent.get("source")?.asString.orEmpty()
            if (videoUrl.isEmpty()) {
                throw Exception("No se encontro source en JSON de VOE")
            }

            // Captions con null-safety
            val captionsElement = decryptedContent.get("captions")
            if (captionsElement != null && captionsElement.isJsonArray) {
                val captionsArray = captionsElement.asJsonArray
                for (i in 0 until captionsArray.size()) {
                    try {
                        val caption = captionsArray.get(i).asJsonObject
                        val label = caption.get("label")?.asString ?: continue
                        val file = caption.get("file")?.asString ?: continue
                        subtitles.add(Video.Subtitle(label = label, file = file))
                    } catch (_: Exception) { }
                }
            }
        } else if (result.startsWith("STATE:")) {
            val stateStr = result.removePrefix("STATE:")
            val regex = Regex("""(https://[^"'<>\s]+\.(?:mp4|m3u8)[^"'<>\s]*)""")
            videoUrl = regex.find(stateStr)?.groupValues?.get(1)
                ?: throw Exception("No se encontro URL de video en state de VOE")
        } else if (result.startsWith("DIRECT:")) {
            val videoUrl = result.removePrefix("DIRECT:")
            LogCollector.log("SUCCESS", "[VOE] URL directa (parseResult): ${videoUrl.take(80)}...")
            return Video(source = videoUrl, type = if (videoUrl.contains(".m3u8")) "application/x-mpegURL" else "video/mp4")
        } else {
            throw Exception("Formato desconocido de resultado VOE: ${result.take(50)}")
        }

        LogCollector.log("SUCCESS", "[VOE] ${videoUrl.take(80)}...")
        return Video(source = videoUrl, subtitles = subtitles)
    }

    override suspend fun extract(link: String): Video {
        val html = HttpHelper.httpGet(link)

        if (html.contains("altcha") || html.contains("Confirm you're human")) {
            throw Exception("VOE requiere WebView (captcha ALTCHA)")
        }

        val encodedString = DecryptHelper.findEncodedRegex(html)
        val decryptedContent: JsonObject = if (encodedString != null) {
            DecryptHelper.decrypt(encodedString)
        } else {
            val doc = Jsoup.parse(html)
            val scriptData = doc.selectFirst("script[type=application/json]")?.data()?.trim().orEmpty()
            DecryptHelper.decrypt(scriptData)
        }

        val m3u8 = decryptedContent.get("source")?.asString.orEmpty()
        if (m3u8.isEmpty()) {
            throw Exception("No se encontro source en JSON de VOE")
        }

        val doc = Jsoup.parse(html)
        val baseSubtitleScript = doc.selectFirst("script")?.data() ?: ""
        var baseSubtitle = ""
        if (baseSubtitleScript.isNotBlank()) {
            val regex = Regex("""var\s+base\s*=\s*['"]([^'"]+)['"]""")
            baseSubtitle = regex.find(baseSubtitleScript)?.groupValues?.get(1) ?: ""
        }

        val subtitles = mutableListOf<Video.Subtitle>()
        val captionsElement = decryptedContent.get("captions")
        if (captionsElement != null && captionsElement.isJsonArray) {
            val captionsArray = captionsElement.asJsonArray
            for (i in 0 until captionsArray.size()) {
                try {
                    val caption = captionsArray.get(i).asJsonObject
                    val label = caption.get("label")?.asString ?: continue
                    var file = caption.get("file")?.asString ?: continue
                    if (!file.startsWith("http")) file = baseSubtitle + file
                    subtitles.add(Video.Subtitle(label = label, file = file))
                } catch (_: Exception) { }
            }
        }

        return Video(source = m3u8, subtitles = subtitles)
    }
}
