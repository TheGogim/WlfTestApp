package com.wlftest.extractors

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wlftest.ui.LogCollector
import org.jsoup.Jsoup

/**
 * VOE extractor.
 * 2025-08: VOE tiene captcha ALTCHA (proof-of-work, no visual).
 * Se resuelve automaticamente via JS en el WebView.
 *
 * La data del video esta encriptada en un <script type="application/json">.
 * Como esta encriptada (rot13+base64+shift), NO se puede buscar por 'source' o 'mp4'
 * en el texto crudo. Hay que buscar el script por tamano y tipo, luego desencriptar.
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

        // Preload JS: interceptar XHR/fetch por si la pagina entrega la URL via API
        val preloadJs = """
            (function() {
                window.__voeData = null;
                var origXHROpen = XMLHttpRequest.prototype.open;
                var origXHRSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__voeUrl = url;
                    return origXHROpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function(body) {
                    var self = this;
                    this.addEventListener('load', function() {
                        if (self.responseText && self.responseText.length > 50) {
                            window.__voeData = 'XHR:' + (self.__voeUrl || '') + ' -> ' + self.responseText.substring(0, 3000);
                        }
                    });
                    return origXHRSend.apply(this, arguments);
                };
                if (window.fetch) {
                    var origFetch = window.fetch.bind(window);
                    window.fetch = function(input) {
                        return origFetch.apply(this, arguments).then(function(resp) {
                            var url = (typeof input === 'string') ? input : (input.url || '');
                            var cloned = resp.clone();
                            cloned.text().then(function(text) {
                                if (text && text.length > 50) {
                                    window.__voeData = 'FETCH:' + url + ' -> ' + text.substring(0, 3000);
                                }
                            });
                            return resp;
                        });
                    };
                }
            })();
        """.trimIndent()

        val extractJs = """
            (function() {
                try {
                    var html = document.documentElement.innerHTML;
                    var scripts = document.querySelectorAll('script');
                    var altchaPresent = !!document.querySelector('altcha-widget') || html.indexOf('altcha') !== -1;
                    var humanInTitle = document.title && document.title.indexOf('human') !== -1;

                    // Metodo 1: Buscar <script type="application/json"> con contenido significativo
                    // NOTA: La data esta ENCRYPTADA, NO buscar 'source'/'mp4' en texto crudo
                    for (var i = 0; i < scripts.length; i++) {
                        if (scripts[i].getAttribute('type') === 'application/json') {
                            var text = scripts[i].textContent || '';
                            // La data encriptada de VOE es >200 chars y contiene los patrones
                            // de encriptacion (@$, ^^, ~@, etc.) o es suficientemente larga
                            if (text.length > 200) {
                                return 'ENCODED:' + text;
                            }
                        }
                    }

                    // Metodo 2: Buscar en TODOS los scripts inline largos que tengan patrones de VOE
                    // (patrones del DecryptHelper: @$, ^^, ~@, %?, *~, !!, #&)
                    for (var i = 0; i < scripts.length; i++) {
                        var text = scripts[i].textContent || '';
                        if (text.length > 300 && (text.indexOf('@$') !== -1 || text.indexOf('^^') !== -1 || text.indexOf('~@') !== -1)) {
                            return 'ENCODED:' + text;
                        }
                    }

                    // Metodo 3: URL directa en el HTML
                    var m3u8Match = html.match(/https?:\/\/[^"'<>\s]+\.m3u8[^"'<>\s]*/);
                    if (m3u8Match) return 'DIRECT:' + m3u8Match[0];
                    var mp4Match = html.match(/https?:\/\/[^"'<>\s]+\.mp4[^"'<>\s]*/);
                    if (mp4Match) return 'DIRECT:' + mp4Match[0];

                    // Metodo 4: Variables globales comunes
                    var stateVars = ['__INITIAL_STATE__', '__NUXT__', '__NEXT_DATA__', 'playerConfig', 'videoData'];
                    for (var v = 0; v < stateVars.length; v++) {
                        try {
                            var val = window[stateVars[v]];
                            if (val) {
                                var str = JSON.stringify(val);
                                if (str.length > 50 && (str.indexOf('mp4') !== -1 || str.indexOf('m3u8') !== -1 || str.indexOf('source') !== -1)) {
                                    return 'STATE:' + str;
                                }
                            }
                        } catch(e) {}
                    }

                    // Metodo 5: Video element
                    var video = document.querySelector('video');
                    if (video) {
                        var src = video.src || video.currentSrc || '';
                        if (src && src.indexOf('http') === 0) return 'DIRECT:' + src;
                        var sources = video.querySelectorAll('source');
                        for (var s = 0; s < sources.length; s++) {
                            src = sources[s].src || sources[s].getAttribute('src') || '';
                            if (src && src.indexOf('http') === 0) return 'DIRECT:' + src;
                        }
                    }

                    // Metodo 6: Datos capturados por XHR/fetch
                    if (window.__voeData) {
                        var voeData = window.__voeData;
                        // Si contiene URL de video directo
                        var urlMatch = voeData.match(/https?:\/\/[^"'<>\s]+\.(?:mp4|m3u8)[^"'<>\s]*/);
                        if (urlMatch) return 'DIRECT:' + urlMatch[0];
                        // Si contiene JSON con source
                        if (voeData.indexOf('source') !== -1) return 'ENCODED:' + voeData;
                        // Loggear lo que capturamos
                        return 'XHR_DATA:' + voeData.substring(0, 500);
                    }

                    // Si ALTCHA sigue presente, reportar
                    if (altchaPresent || humanInTitle) {
                        return 'CAPTCHA_STILL_LOADING';
                    }

                    // Debug: retornar informacion de la pagina
                    return 'WAITING:scripts=' + scripts.length +
                           ' html_len=' + html.length +
                           ' title=' + (document.title || 'none') +
                           ' altcha=' + altchaPresent +
                           ' appjson=' + document.querySelectorAll('script[type="application/json"]').length;
                } catch(e) {
                    return 'ERROR:' + e.message;
                }
            })()
        """.trimIndent()

        // Intentar con tiempos crecientes: ALTCHA puede tardar 3-8s en resolverse
        val attempts = listOf(
            8000L to 5000L,
            15000L to 5000L,
            22000L to 3000L
        )

        for ((waitMs, extraMs) in attempts) {
            try {
                val result = WebViewHelper.evaluate(
                    context = context,
                    url = link,
                    js = extractJs,
                    preloadJs = preloadJs,
                    waitForMs = waitMs,
                    extraJsWaitMs = extraMs
                )

                LogCollector.log("WEBVIEW", "[VOE] intento (${waitMs}ms): ${result.take(200)}")

                if (result.isEmpty() || result == "null") {
                    LogCollector.log("WARN", "[VOE] Resultado vacio, reintentando...")
                    continue
                }

                if (result.startsWith("ERROR:")) {
                    LogCollector.log("WARN", "[VOE] Error en JS: ${result.take(100)}")
                    continue
                }

                if (result.startsWith("CAPTCHA_STILL_LOADING")) {
                    LogCollector.log("WARN", "[VOE] ALTCHA todavia cargando...")
                    continue
                }

                if (result.startsWith("WAITING:") || result.startsWith("XHR_DATA:")) {
                    LogCollector.log("DEBUG", "[VOE] Info: ${result.take(300)}")
                    continue
                }

                // Tenemos algo util: DIRECT, ENCODED, STATE
                return parseResult(result)

            } catch (e: Exception) {
                LogCollector.log("WARN", "[VOE] Intento fallo: ${e.message}")
            }
        }

        throw Exception("VOE: no se pudo extraer video despues de varios intentos")
    }

    private fun parseResult(result: String): Video {
        val videoUrl: String
        val subtitles = mutableListOf<Video.Subtitle>()

        if (result.startsWith("ENCODED:")) {
            val encodedString = result.removePrefix("ENCODED:")

            // Si viene de XHR/fetch, puede tener prefijo "XHR:... -> "
            var pureData = encodedString
            val arrowIdx = encodedString.indexOf(" -> ")
            if (arrowIdx !== -1 && arrowIdx < 100) {
                pureData = encodedString.substring(arrowIdx + 4)
            }

            val decryptedContent: JsonObject = try {
                DecryptHelper.decrypt(pureData)
            } catch (_: Exception) {
                try {
                    JsonParser.parseString(pureData).asJsonObject
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
            LogCollector.log("SUCCESS", "[VOE] URL directa: ${videoUrl.take(80)}...")
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
