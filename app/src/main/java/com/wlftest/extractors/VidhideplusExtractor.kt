package com.wlftest.extractors

import android.content.Context
import com.wlftest.ui.LogCollector

/**
 * Vidhideplus / Vidhide extractor.
 * Usa JWPlayer con JS packed (eval con packer).
 *
 * Estrategia: WebView para ejecutar el JS del sitio y extraer
 * la URL del video del player JWPlayer una vez inicializado.
 */
class VidhideplusExtractor : Extractor() {
    override val name = "Vidhideplus"
    override val mainUrl = "https://vidhideplus.com"
    override val aliasUrls = listOf("https://vidhide.com", "https://vidhideplus.com")
    override val needsWebView = true

    override suspend fun extract(link: String): Video {
        // Fallback HTTP: intentar con JsUnpacker
        val html = HttpHelper.httpGet(link)
        return extractFromHtml(html, link)
    }

    override suspend fun extractWithWebView(link: String, context: Context): Video {
        // Preload JS: interceptar la configuracion de JWPlayer para capturar la fuente
        val preloadJs = """
            (function() {
                window.__vhSources = [];
                window.__vhCaptured = null;

                // Interceptar jwplayer().setup()
                var origJwSetup = null;
                var jwCheckInterval = setInterval(function() {
                    try {
                        if (typeof jwplayer !== 'undefined' && jwplayer && jwplayer().setup) {
                            var origSetup = jwplayer().setup.bind(jwplayer());
                            jwplayer().setup = function(config) {
                                // Capturar sources del setup
                                if (config && config.file) {
                                    window.__vhCaptured = config.file;
                                }
                                if (config && config.sources) {
                                    for (var i = 0; i < config.sources.length; i++) {
                                        if (config.sources[i].file) {
                                            window.__vhSources.push(config.sources[i].file);
                                        }
                                    }
                                }
                                return origSetup(config);
                            };
                            clearInterval(jwCheckInterval);
                        }
                    } catch(e) {}
                }, 100);
            })();
        """.trimIndent()

        val extractJs = """
            (function() {
                try {
                    // 1. URL capturada del setup
                    if (window.__vhCaptured && window.__vhCaptured.indexOf('http') === 0) {
                        return 'URL:' + window.__vhCaptured;
                    }

                    // 2. Sources capturadas
                    if (window.__vhSources && window.__vhSources.length > 0) {
                        for (var i = 0; i < window.__vhSources.length; i++) {
                            if (window.__vhSources[i].indexOf('.m3u8') !== -1 ||
                                window.__vhSources[i].indexOf('.mp4') !== -1) {
                                return 'URL:' + window.__vhSources[i];
                            }
                        }
                        // Si no hay m3u8/mp4 explicito, usar la primera
                        return 'URL:' + window.__vhSources[0];
                    }

                    // 3. Intentar obtener del JWPlayer directamente
                    try {
                        var player = jwplayer();
                        if (player && player.getPlaylist) {
                            var playlist = player.getPlaylist();
                            if (playlist && playlist.length > 0) {
                                var item = playlist[0];
                                if (item && item.file && item.file.indexOf('http') === 0) {
                                    return 'URL:' + item.file;
                                }
                                if (item && item.sources) {
                                    for (var i = 0; i < item.sources.length; i++) {
                                        var f = item.sources[i].file || '';
                                        if (f.indexOf('.m3u8') !== -1 || f.indexOf('.mp4') !== -1) {
                                            return 'URL:' + f;
                                        }
                                    }
                                }
                            }
                        }
                    } catch(e) {}

                    // 4. Buscar URLs de video en el HTML
                    var html = document.documentElement.innerHTML;
                    var m3u8 = html.match(/https?:\/\/[^"'<>\s]+\.m3u8[^"'<>\s]*/);
                    if (m3u8) return 'URL:' + m3u8[0];
                    var mp4 = html.match(/https?:\/\/[^"'<>\s]+\.mp4[^"'<>\s]*/);
                    if (mp4) return 'URL:' + mp4[0];

                    // 5. Debug
                    return 'DEBUG:captured=' + (window.__vhCaptured || 'null') +
                           ' sources=' + JSON.stringify(window.__vhSources) +
                           ' jwexists=' + (typeof jwplayer !== 'undefined') +
                           ' html_len=' + html.length;
                } catch(e) {
                    return 'ERROR:' + e.message;
                }
            })();
        """.trimIndent()

        // Multiples intentos con esperas crecientes
        val attempts = listOf(
            4000L to 2000L,
            7000L to 3000L,
            10000L to 3000L
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

                LogCollector.log("WEBVIEW", "Vidhideplus intento (${waitMs}ms): ${result.take(300)}")

                if (result.startsWith("URL:")) {
                    val videoUrl = result.removePrefix("URL:")
                    LogCollector.log("SUCCESS", "[Vidhideplus] $videoUrl")
                    return Video(
                        source = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) "application/x-mpegURL" else "video/mp4",
                        headers = mapOf("Referer" to link)
                    )
                }

                if (result.startsWith("DEBUG:")) {
                    LogCollector.log("DEBUG", "Vidhideplus: $result")
                }
            } catch (e: Exception) {
                LogCollector.log("WARN", "Vidhideplus intento fallo: ${e.message}")
            }
        }

        // Fallback HTTP
        LogCollector.log("WARN", "Vidhideplus WebView fallo, intentando HTTP fallback...")
        val html = try {
            HttpHelper.httpGet(link)
        } catch (e: Exception) {
            throw Exception("Vidhideplus: WebView y HTTP fallaron")
        }
        return extractFromHtml(html, link)
    }

    private fun extractFromHtml(html: String, link: String): Video {
        // Buscar el bloque eval completo (packed JS)
        val evalBlock = Regex(
            "eval\\(function\\(p,a,c,k,e,d\\)\\{.*?\\}\\('.*?'\\)\\)"
        ).find(html)?.value

        if (evalBlock != null) {
            val unpacker = JsUnpacker(evalBlock)
            if (unpacker.detect()) {
                val unpacked = unpacker.unpack()
                if (unpacked != null) {
                    LogCollector.log("DEBUG", "Vidhideplus: JS desempacado (${unpacked.length} chars)")

                    // Buscar URLs en el codigo desempacado
                    val m3u8 = Regex("""https?://[^"'<>\s]+\.m3u8[^"'<>\s]*""").find(unpacked)?.value
                    if (m3u8 != null) {
                        LogCollector.log("SUCCESS", "[Vidhideplus] m3u8 del unpacked: $m3u8")
                        return Video(
                            source = m3u8,
                            type = "application/x-mpegURL",
                            headers = mapOf("Referer" to link)
                        )
                    }

                    // Buscar patrón de fuente: file:"..." o 'file':"..."
                    val filePatterns = listOf(
                        Regex("""["']file["']\s*:\s*["'](https?://[^"']+)["']"""),
                        Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                    )
                    for (pat in filePatterns) {
                        val match = pat.find(unpacked)
                        if (match != null) {
                            val url = match.groupValues[1]
                            LogCollector.log("SUCCESS", "[Vidhideplus] URL del unpacked: $url")
                            return Video(
                                source = url,
                                type = if (url.contains(".m3u8")) "application/x-mpegURL" else "video/mp4",
                                headers = mapOf("Referer" to link)
                            )
                        }
                    }

                    // Log del unpacked para debug
                    LogCollector.log("DEBUG", "Vidhideplus unpacked (primeros 500): ${unpacked.take(500)}")
                }
            }
        }

        // Buscar URLs directamente en el HTML
        val m3u8 = Regex("""https?://[^"'<>\s]+\.m3u8[^"'<>\s]*""").find(html)?.value
        if (m3u8 != null) {
            LogCollector.log("SUCCESS", "[Vidhideplus] m3u8 directo: $m3u8")
            return Video(
                source = m3u8,
                type = "application/x-mpegURL",
                headers = mapOf("Referer" to link)
            )
        }

        throw Exception("Vidhideplus: No se encontro URL de video")
    }
}
