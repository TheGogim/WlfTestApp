package com.wlftest.extractors

import android.content.Context
import com.wlftest.ui.LogCollector
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Ok.ru extractor 2025-08.
 *
 * Problema: Ok.ru usa AMD modules que capturan referencias a XHR
 * antes de que nuestro preload JS pueda sobreescribir los prototipos.
 *
 * Solucion: shouldInterceptRequest captura requests a nivel de red
 * (independientemente de como se inicien), buscando la respuesta del
 * metadata API que contiene las URLs de video.
 */
class OkruExtractor : Extractor() {
    override val name = "Okru"
    override val mainUrl = "https://ok.ru"
    override val needsWebView = true

    // Patrones de URLs de Ok.ru que contienen metadata de video
    private val metadataUrlPatterns = listOf(
        "/videoembed/",
        "/api/",
        "/metadata",
        "/video/"
    )

    override suspend fun extractWithWebView(link: String, context: Context): Video {
        LogCollector.log("WEBVIEW", "[Okru] Cargando embed via WebView...")

        // Extraer el video ID de la URL
        val videoId = extractVideoId(link)
        LogCollector.log("EXTRACTOR", "[Okru] Video ID: $videoId")

        // --- Estrategia 1: shouldInterceptRequest para capturar metadata ---
        val capturedResponses = mutableListOf<String>()
        val preloadJs = """
            (function() {
                window.__okruCaptured = [];
                window.__okruAllScripts = [];

                // Intentar interceptar XHR de todas formas (para Some cases)
                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__okruUrl = url;
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function(body) {
                    var self = this;
                    this.addEventListener('load', function() {
                        if (self.__okruUrl && self.responseText) {
                            var text = self.responseText;
                            if (text.indexOf('.mp4') !== -1 || text.indexOf('.m3u8') !== -1 ||
                                text.indexOf('"videos"') !== -1 || text.indexOf('flashvars') !== -1 ||
                                text.indexOf('metadata') !== -1) {
                                window.__okruCaptured.push(text);
                            }
                        }
                    });
                    return origSend.apply(this, arguments);
                };

                if (window.fetch) {
                    var origFetch = window.fetch.bind(window);
                    window.fetch = function(input) {
                        return origFetch.apply(this, arguments).then(function(resp) {
                            var url = (typeof input === 'string') ? input : (input.url || '');
                            if (url.indexOf('video') !== -1 || url.indexOf('metadata') !== -1) {
                                var cloned = resp.clone();
                                cloned.text().then(function(text) {
                                    if (text && (text.indexOf('.mp4') !== -1 || text.indexOf('.m3u8') !== -1 ||
                                        text.indexOf('"videos"') !== -1 || text.indexOf('flashvars') !== -1)) {
                                        window.__okruCaptured.push(text);
                                    }
                                });
                            }
                            return resp;
                        });
                    };
                }

                // Capturar scripts insertados
                var origAppendChild = Node.prototype.appendChild;
                Node.prototype.appendChild = function(child) {
                    if (child && child.tagName && child.tagName.toLowerCase() === 'script') {
                        var src = child.src || child.getAttribute('src') || '';
                        var content = child.textContent || '';
                        if (src) window.__okruAllScripts.push('src:' + src);
                        if (content.length > 50) window.__okruAllScripts.push('inline:' + content.length + 'chars');
                    }
                    return origAppendChild.call(this, child);
                };
            })();
        """.trimIndent()

        val extractJs = """
            (function() {
                try {
                    // 1. Datos capturados por interceptor
                    if (window.__okruCaptured && window.__okruCaptured.length > 0) {
                        for (var i = 0; i < window.__okruCaptured.length; i++) {
                            var data = window.__okruCaptured[i];
                            if (data.length > 50) return 'XHR_DATA:' + data;
                        }
                    }

                    // 2. data-options (a veces aparece despues de carga dinamica)
                    var el = document.querySelector('[data-options]');
                    if (el) return 'DATA_OPTIONS:' + el.getAttribute('data-options');

                    // 3. flashvars globales
                    if (typeof flashvars !== 'undefined' && flashvars) {
                        return 'FLASHVARS:' + JSON.stringify(flashvars);
                    }

                    // 4. OK.VideoPlayer
                    if (window.OK && window.OK.VideoPlayer && window.OK.VideoPlayer.player_ &&
                        window.OK.VideoPlayer.player_.flashvars) {
                        return 'PLAYER_DATA:' + JSON.stringify({flashvars: window.OK.VideoPlayer.player_.flashvars});
                    }

                    // 5. Buscar en window.OKSDK o variables modernas
                    if (window.OK) {
                        var keys = Object.keys(window.OK);
                        for (var i = 0; i < keys.length; i++) {
                            try {
                                var val = JSON.stringify(window.OK[keys[i]]);
                                if (val && val.indexOf('.mp4') !== -1) return 'OK_DATA:' + keys[i] + '=' + val;
                            } catch(e) {}
                        }
                    }

                    // 6. Buscar videos en todos los scripts del DOM
                    var scripts = document.querySelectorAll('script');
                    for (var i = 0; i < scripts.length; i++) {
                        var text = scripts[i].textContent || '';
                        if ((text.indexOf('"videos"') !== -1 || text.indexOf('flashvars') !== -1 ||
                             text.indexOf('.mp4') !== -1 || text.indexOf('.m3u8') !== -1) && text.length > 100) {
                            return 'SCRIPT_DATA:' + text;
                        }
                    }

                    // 7. og:video
                    var ogVideo = document.querySelector('meta[property="og:video:secure_url"]') ||
                                   document.querySelector('meta[property="og:video"]');
                    if (ogVideo) {
                        var content = ogVideo.getAttribute('content') || '';
                        if (content.indexOf('http') === 0) return 'OG_VIDEO:' + content;
                    }

                    // 8. Buscar video element
                    var video = document.querySelector('video');
                    if (video) {
                        var src = video.src || video.currentSrc || '';
                        if (src && src.indexOf('http') === 0) return 'DIRECT:' + src;
                    }

                    // 9. URLs directas en HTML
                    var html = document.documentElement.innerHTML;
                    var videoUrls = html.match(/https?:\/\/[^"'<>\s]+\.(?:mp4|m3u8)[^"'<>\s]*/);
                    if (videoUrls) return 'DIRECT:' + videoUrls[0];

                    // 10. Debug info
                    return 'WAITING:scripts=' + scripts.length +
                           ' captured=' + (window.__okruCaptured ? window.__okruCaptured.length : 0);
                } catch(e) {
                    return 'ERROR:' + e.message;
                }
            })();
        """.trimIndent()

        // Estrategia: multiples intentos con esperas largas
        // Ok.ru carga muchos scripts AMD que tardan en ejecutarse
        val attempts = listOf(
            6000L to 4000L,
            12000L to 5000L,
            18000L to 5000L
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

                LogCollector.log("WEBVIEW", "[Okru] intento (${waitMs}ms): ${result.take(300)}")

                val videoUrl = parseOkruResult(result, link)
                if (videoUrl != null) {
                    LogCollector.log("SUCCESS", "[Okru] ${videoUrl.take(80)}...")
                    return Video(
                        source = videoUrl,
                        headers = mapOf("Referer" to mainUrl, "User-Agent" to HttpHelper.UA)
                    )
                }

                if (result.startsWith("WAITING:")) {
                    LogCollector.log("DEBUG", "[Okru] Esperando mas... ${result}")
                }

            } catch (e: Exception) {
                LogCollector.log("WARN", "[Okru] intento fallo: ${e.message}")
            }
        }

        // --- Estrategia 2: fetch directo del metadata API ---
        LogCollector.log("EXTRACTOR", "[Okru] WebView fallo, intentando metadata API directo...")
        try {
            val metadataUrl = extractMetadataFromHttp(link)
            if (metadataUrl != null) return metadataUrl
        } catch (e: Exception) {
            LogCollector.log("WARN", "[Okru] Metadata API fallo: ${e.message}")
        }

        throw Exception("No se encontraron URLs de video en ok.ru")
    }

    override suspend fun extract(link: String): Video {
        val html = HttpHelper.httpGet(link)
        val doc = Jsoup.parse(html)

        val videoString = doc.selectFirst("div[data-options]")?.attr("data-options")
        if (videoString != null) {
            return parseFromDataOptions(videoString)
        }

        val ogVideo = doc.selectFirst("meta[property=og:video:secure_url]")
            ?.attr("content")
        if (ogVideo != null && ogVideo.startsWith("https://")) {
            LogCollector.log("SUCCESS", "[Okru] og:video: ${ogVideo.take(80)}...")
            return Video(
                source = ogVideo,
                headers = mapOf("Referer" to mainUrl, "User-Agent" to HttpHelper.UA)
            )
        }

        throw Exception("No se encontro data-options en ok.ru. Se necesita WebView.")
    }

    private fun parseOkruResult(result: String, originalLink: String): String? {
        return when {
            result.startsWith("XHR_DATA:") -> parseVideoData(result.removePrefix("XHR_DATA:"))
            result.startsWith("DATA_OPTIONS:") -> parseVideoData(result.removePrefix("DATA_OPTIONS:"))
            result.startsWith("FLASHVARS:") -> parseVideoData(result.removePrefix("FLASHVARS:"))
            result.startsWith("PLAYER_DATA:") -> parseVideoData(result.removePrefix("PLAYER_DATA:"))
            result.startsWith("SCRIPT_DATA:") -> parseVideoData(result.removePrefix("SCRIPT_DATA:"))
            result.startsWith("OK_DATA:") -> parseVideoData(result.removePrefix("OK_DATA:"))
            result.startsWith("OG_VIDEO:") -> result.removePrefix("OG_VIDEO:")
            result.startsWith("DIRECT:") -> result.removePrefix("DIRECT:")
            result.isEmpty() || result == "null" -> null
            result.startsWith("WAITING:") -> null
            result.startsWith("ERROR:") -> {
                LogCollector.log("ERROR", "[Okru] JS error: ${result.removePrefix("ERROR:")}")
                null
            }
            else -> {
                // Intentar parsear como JSON o buscar URLs directamente
                parseVideoData(result)
            }
        }
    }

    /**
     * Intenta obtener URLs de video via HTTP directo al metadata API de Ok.ru.
     */
    private suspend fun extractMetadataFromHttp(embedLink: String): Video? {
        val videoId = extractVideoId(embedLink) ?: return null

        // Ok.ru tiene un endpoint de metadata que retorna JSON con videos
        // Format: https://ok.ru/videoembed/{id} -> extraer metadata
        val html = HttpHelper.httpGet(embedLink)
        val doc = Jsoup.parse(html)

        // Buscar data-options
        val dataOptions = doc.selectFirst("div[data-options]")?.attr("data-options")
        if (dataOptions != null) {
            return parseFromDataOptions(dataOptions)
        }

        // Buscar flashvars en scripts
        val scripts = doc.select("script")
        for (script in scripts) {
            val text = script.data()
            if (text.contains("flashvars") || text.contains("videos")) {
                val url = parseVideoData(text)
                if (url != null) {
                    return Video(
                        source = url,
                        headers = mapOf("Referer" to mainUrl, "User-Agent" to HttpHelper.UA)
                    )
                }
            }
        }

        // Buscar og:video
        val ogVideo = doc.selectFirst("meta[property=og:video:secure_url]")
            ?.attr("content")
            ?: doc.selectFirst("meta[property=og:video]")?.attr("content")
        if (ogVideo != null && ogVideo.startsWith("https://")) {
            return Video(
                source = ogVideo,
                headers = mapOf("Referer" to mainUrl, "User-Agent" to HttpHelper.UA)
            )
        }

        return null
    }

    private fun parseVideoData(data: String): String? {
        return try {
            val json = JSONObject(data)
            when {
                json.has("flashvars") -> {
                    val flashvars = json.getJSONObject("flashvars")
                    when {
                        flashvars.has("videos") -> parseVideosArray(flashvars.getString("videos"))
                        flashvars.has("metadata") -> {
                            val meta = flashvars.getJSONObject("metadata")
                            if (meta.has("videos")) parseVideosArray(meta.getString("videos")) else null
                        }
                        else -> null
                    }
                }
                json.has("videos") -> parseVideosArray(json.getString("videos"))
                json.has("source") -> json.getString("source")
                else -> extractDirectVideoUrls(data)
            }
        } catch (_: Exception) {
            extractDirectVideoUrls(data)
        }
    }

    private fun parseVideosArray(videosStr: String): String? {
        val qualities = listOf("ultra", "quad", "full", "hd", "sd", "low", "lowest", "mobile")

        for (quality in qualities) {
            val nameKey = "\"name\":\"$quality\""
            val nameIdx = videosStr.indexOf(nameKey)
            if (nameIdx < 0) continue

            val urlKeys = listOf("\"url\":\"", "url:\"", "\"url\":")
            for (urlKey in urlKeys) {
                val uIdx = videosStr.indexOf(urlKey, nameIdx)
                if (uIdx > -1) {
                    val start = uIdx + urlKey.length
                    val end = videosStr.indexOf('"', start)
                    if (end > start) {
                        var url = videosStr.substring(start, end)
                        url = url.replace("\\u0026", "&")
                        if (url.startsWith("https://")) return url
                    }
                }
            }
        }

        return extractDirectVideoUrls(videosStr)
    }

    private fun extractDirectVideoUrls(text: String): String? {
        val mp4Regex = Regex("""(https://[^"'<>\s]+\.(?:mp4|m3u8)[^"'<>\s]*)""")
        val urls = mp4Regex.findAll(text).map { it.groupValues[1] }.filter {
            it.contains("ok.") || it.contains("cdn") || it.contains("vk") || it.contains("st-")
        }.toList()
        return urls.firstOrNull()
    }

    private fun parseFromDataOptions(optionsStr: String): Video {
        val arrayData = optionsStr
            .substringAfterLast("name:\"")
            .substringBefore("]")

        val videos = arrayData.split("{name:\"").reversed().mapNotNull {
            val videoUrl = it
                .substringAfter("url:\"")
                .substringBefore("\"")
                .replace("\u0026", "&")
            val quality = fixQuality(it.substringBefore("\""))
            if (videoUrl.startsWith("https://")) Pair(quality, videoUrl) else null
        }

        if (videos.isEmpty()) throw Exception("No videos validos en ok.ru")
        val best = videos.first().second

        LogCollector.log("SUCCESS", "[Okru] ${videos.first().first}: ${best.take(80)}...")
        return Video(
            source = best,
            headers = mapOf("Referer" to mainUrl, "User-Agent" to HttpHelper.UA)
        )
    }

    private fun extractVideoId(link: String): String? {
        // https://ok.ru/videoembed/15086933576276 -> 15086933576276
        val regex = Regex("""/videoembed/(\d+)""")
        return regex.find(link)?.groupValues?.get(1)
    }

    private fun fixQuality(quality: String) = when (quality) {
        "ultra" -> "2160p"
        "quad" -> "1440p"
        "full" -> "1080p"
        "hd" -> "720p"
        "sd" -> "480p"
        "low" -> "360p"
        "lowest" -> "240p"
        "mobile" -> "144p"
        else -> quality
    }
}
