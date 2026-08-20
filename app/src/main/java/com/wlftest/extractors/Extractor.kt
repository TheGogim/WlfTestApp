package com.wlftest.extractors

import android.content.Context
import android.util.Base64
import com.wlftest.ui.LogCollector
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Router de extractors.
 */
abstract class Extractor {
    abstract val name: String
    abstract val mainUrl: String
    open val aliasUrls: List<String> = emptyList()
    open val rotatingDomain: List<Regex> = emptyList()
    open val needsWebView: Boolean = false

    abstract suspend fun extract(link: String): Video

    open suspend fun extractWithWebView(link: String, context: Context): Video {
        return extract(link)
    }

    companion object {
        private val extractors = listOf(
            RpmvidExtractor(),
            FilemoonExtractor(),
            SavefilesExtractor(),
            OkruExtractor(),
            VoeExtractor(),
            VidsonicExtractor(),
            VidGuardExtractor(),
            VidaraExtractor(),
            TurboViplayExtractor(),
            VidhideplusExtractor(),
        )

        suspend fun extract(link: String, providerName: String = "", context: Context? = null): Video {
            var finalLink = link

            if (providerName == "TioPlus" && link.contains("/player/")) {
                // La pagina /player/ es simple: solo retorna window.location.href = 'real-url'
                // HTTP es mas rapido y confiable que WebView para este caso.
                LogCollector.log("EXTRACTOR", "Resolviendo wrapper /player/ de TioPlus via HTTP...")
                val httpResult = resolveTioPlusPlayerHttp(link)
                if (httpResult != link) {
                    finalLink = httpResult
                    LogCollector.log("EXTRACTOR", "URL real resuelta: $finalLink")
                } else if (context != null) {
                    // Fallback a WebView solo si HTTP no encontro nada
                    LogCollector.log("WARN", "HTTP no resolvio /player/, intentando WebView...")
                    val resolved = resolveTioPlusPlayerWebView(link, context)
                    if (resolved != null) {
                        finalLink = resolved
                        LogCollector.log("EXTRACTOR", "URL real resuelta (WebView): $finalLink")
                    }
                }
            }

            val urlRegex = Regex("^(https?://)?(www\\.)?")
            val compareUrl = finalLink.lowercase().replace(urlRegex, "")

            var found: Extractor? = null
            for (ext in extractors) {
                if (compareUrl.startsWith(ext.mainUrl.lowercase().replace(urlRegex, ""))) {
                    found = ext; break
                }
                for (alias in ext.aliasUrls) {
                    if (compareUrl.startsWith(alias.lowercase().replace(urlRegex, ""))) {
                        found = ext; break
                    }
                }
                if (found != null) break
            }

            if (found == null) {
                for (ext in extractors) {
                    val extDomain = ext.mainUrl.replace(Regex("^(https?://)?(www\\.)?(.*?)(\\.[a-z]+)"), "$3")
                    if (compareUrl.startsWith(extDomain.lowercase())) {
                        found = ext; break
                    }
                    for (alias in ext.aliasUrls) {
                        val aliasDomain = alias.replace(Regex("^(https?://)?(www\\.)?(.*?)(\\.[a-z]+)"), "$3")
                        if (compareUrl.startsWith(aliasDomain.lowercase())) {
                            found = ext; break
                        }
                    }
                    if (found != null) break
                }
            }

            if (found == null) {
                for (ext in extractors) {
                    if (ext.rotatingDomain.any { it.containsMatchIn(compareUrl) }) {
                        found = ext; break
                    }
                }
            }

            if (found != null) {
                LogCollector.log("EXTRACTOR", "Usando ${found.name} para $finalLink")
                val video = if (found.needsWebView && context != null) {
                    found.extractWithWebView(finalLink, context)
                } else {
                    found.extract(finalLink)
                }
                LogCollector.log("SUCCESS", "Extraido [${found.name}]: ${video.source}")
                return video
            }

            throw Exception("No extractor found for URL: $finalLink")
        }

        /**
         * Resuelve /player/ de TioPlus via WebView.
         *
         * PROBLEMA: La pagina se recarga en bucle cada ~400ms.
         * El preload JS debe BLOQUEAR las recargas para que el JS de la pagina
         * pueda ejecutarse y crear el iframe con la URL real.
         */
        private suspend fun resolveTioPlusPlayerWebView(playerUrl: String, context: Context): String? {
            // Preload JS: BLOQUEA recargas y captura todo
            val preloadJs = """
                (function() {
                    window.__tioplusCaptured = [];
                    window.__tioplusScripts = [];
                    window.__tioplusTimers = [];

                    // 1. BLOQUEAR meta refresh tags
                    var origQuerySelectorAll = document.querySelectorAll.bind(document);
                    document.querySelectorAll = function(sel) {
                        var els = origQuerySelectorAll(sel);
                        if (sel && sel.indexOf('meta') !== -1) {
                            var filtered = [];
                            for (var i = 0; i < els.length; i++) {
                                var httpEquiv = (els[i].getAttribute('http-equiv') || '').toLowerCase();
                                if (httpEquiv === 'refresh') {
                                    els[i].remove();
                                } else {
                                    filtered.push(els[i]);
                                }
                            }
                            return filtered;
                        }
                        return els;
                    };
                    // Tambien remover los que ya existen
                    var existingMetas = origQuerySelectorAll('meta[http-equiv="refresh"], meta[http-equiv=refresh]');
                    for (var i = 0; i < existingMetas.length; i++) {
                        try { existingMetas[i].remove(); } catch(e) {}
                    }

                    // 2. BLOQUEAR location.reload y location.replace
                    try {
                        window.location.reload = function() {};
                        window.location.replace = function(url) {
                            if (url && url.indexOf('http') === 0 && url.indexOf('tioplus') === -1) {
                                window.__tioplusCaptured.push('location:' + url);
                            }
                        };
                    } catch(e) {}

                    // 3. INTERCEPTAR setTimeout/setInterval para bloquear recargas
                    var origSetTimeout = window.setTimeout;
                    var origSetInterval = window.setInterval;
                    var origClearTimeout = window.clearTimeout;
                    var origClearInterval = window.clearInterval;

                    window.setTimeout = function(fn, delay) {
                        var id = origSetTimeout(function() {
                            try {
                                var fnStr = (typeof fn === 'string') ? fn : (fn ? fn.toString() : '');
                                if (fnStr.indexOf('location') !== -1 || fnStr.indexOf('reload') !== -1 ||
                                    fnStr.indexOf('redirect') !== -1 || fnStr.indexOf('href') !== -1) {
                                    window.__tioplusTimers.push('blocked_timer: ' + fnStr.substring(0, 200));
                                    return;
                                }
                            } catch(e) {}
                            try { fn(); } catch(e) {}
                        }, delay);
                        return id;
                    };

                    window.setInterval = function(fn, delay) {
                        var fnStr = (typeof fn === 'string') ? fn : (fn ? fn.toString() : '');
                        if (fnStr.indexOf('location') !== -1 || fnStr.indexOf('reload') !== -1 ||
                            fnStr.indexOf('redirect') !== -1 || fnStr.indexOf('href') !== -1 ||
                            fnStr.indexOf('generando') !== -1) {
                            window.__tioplusTimers.push('blocked_interval: ' + fnStr.substring(0, 200));
                            return -1;
                        }
                        var id = origSetInterval(fn, delay);
                        return id;
                    };

                    // 4. INTERCEPTAR createElement para capturar iframes
                    var origCreateElement = document.createElement.bind(document);
                    document.createElement = function(tag) {
                        var el = origCreateElement(tag);
                        if (tag && tag.toLowerCase() === 'iframe') {
                            var origSetAttr = el.setAttribute.bind(el);
                            el.setAttribute = function(name, value) {
                                if (name === 'src' && value && value.indexOf('http') === 0) {
                                    window.__tioplusCaptured.push('iframe:' + value);
                                }
                                return origSetAttr(name, value);
                            };
                            try {
                                var origSrc = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'src');
                                if (origSrc && origSrc.set) {
                                    Object.defineProperty(el, 'src', {
                                        set: function(v) {
                                            if (v && v.indexOf('http') === 0) {
                                                window.__tioplusCaptured.push('iframe:' + v);
                                            }
                                            origSrc.set.call(el, v);
                                        },
                                        get: function() { return origSrc.get.call(el); }
                                    });
                                }
                            } catch(e) {}
                        }
                        return el;
                    };

                    // 5. INTERCEPTAR fetch/XHR para capturar respuestas con URLs
                    var origFetch = window.fetch ? window.fetch.bind(window) : null;
                    if (origFetch) {
                        window.fetch = function(input) {
                            return origFetch.apply(this, arguments).then(function(resp) {
                                var url = (typeof input === 'string') ? input : (input.url || '');
                                if (url.indexOf('tioplus') === -1 && url.indexOf('http') === 0) {
                                    var cloned = resp.clone();
                                    cloned.text().then(function(text) {
                                        if (text && text.length > 20) {
                                            window.__tioplusCaptured.push('fetch:' + url + ' -> ' + text.substring(0, 500));
                                        }
                                    });
                                }
                                return resp;
                            });
                        };
                    }

                    var origXHROpen = XMLHttpRequest.prototype.open;
                    var origXHRSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__tpUrl = url;
                        return origXHROpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function(body) {
                        var self = this;
                        this.addEventListener('load', function() {
                            if (self.__tpUrl && self.responseText) {
                                var url = self.__tpUrl;
                                if (url.indexOf('tioplus') === -1) {
                                    window.__tioplusCaptured.push('xhr:' + url + ' -> ' + self.responseText.substring(0, 500));
                                }
                            }
                        });
                        return origXHRSend.apply(this, arguments);
                    };

                    // 6. INTERCEPTAR script tag insertion para capturar src
                    var origAppendChild = Node.prototype.appendChild;
                    Node.prototype.appendChild = function(child) {
                        if (child && child.tagName && child.tagName.toLowerCase() === 'script') {
                            var src = child.src || child.getAttribute('src') || '';
                            window.__tioplusScripts.push(src || 'inline:' + (child.textContent || '').substring(0, 300));
                        }
                        return origAppendChild.call(this, child);
                    };

                })();
            """.trimIndent()

            // Extraction JS
            val extractJs = """
                (function() {
                    try {
                        // 1. URLs capturadas
                        if (window.__tioplusCaptured && window.__tioplusCaptured.length > 0) {
                            for (var i = 0; i < window.__tioplusCaptured.length; i++) {
                                var c = window.__tioplusCaptured[i];
                                if (c.indexOf('tioplus') === -1 && c.indexOf('http') !== -1) {
                                    // Si es una URL directa (no fetch/xhr response)
                                    if (c.indexOf('iframe:') === 0 || c.indexOf('location:') === 0) {
                                        return 'CAPTURED:' + c;
                                    }
                                    // Si es fetch/xhr, buscar URLs en la respuesta
                                    var urls = c.match(/https?:\/\/[^\s"'<>]+/g);
                                    if (urls) {
                                        for (var j = 0; j < urls.length; j++) {
                                            if (urls[j].indexOf('tioplus') === -1 &&
                                                (urls[j].indexOf('.mp4') !== -1 || urls[j].indexOf('.m3u8') !== -1 ||
                                                 urls[j].indexOf('/e/') !== -1 || urls[j].indexOf('/embed') !== -1 ||
                                                 urls[j].indexOf('/d/') !== -1 || urls[j].indexOf('vid') !== -1 ||
                                                 urls[j].indexOf('ok.ru') !== -1)) {
                                                return 'CAPTURED_URL:' + urls[j];
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Iframes en el DOM
                        var iframes = document.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) {
                            var src = iframes[i].src || iframes[i].getAttribute('src') || '';
                            if (src.indexOf('http') === 0 && src.indexOf('tioplus') === -1) {
                                return 'IFRAME:' + src;
                            }
                        }

                        // 3. URLs de video directas
                        var html = document.documentElement.innerHTML;
                        var mp4Match = html.match(/https?:\/\/[^\s"'<>]+\.(?:mp4|m3u8)[^\s"'<>]*/);
                        if (mp4Match) return 'DIRECT:' + mp4Match[0];

                        // 4. Dominios de embed conocidos
                        var embedDomains = ['filemoon', 'voe', 'ok.ru', 'vidguard', 'vidsonic', 'vidara', 'rpmvid', 'turboviplay', 'uqload', 'doodstream', 'streamtape', 'earnvids', 'upfast'];
                        var allLinks = html.match(/https?:\/\/[^\s"'<>]+/g) || [];
                        for (var i = 0; i < allLinks.length; i++) {
                            for (var j = 0; j < embedDomains.length; j++) {
                                if (allLinks[i].toLowerCase().indexOf(embedDomains[j]) !== -1) {
                                    return 'EMBED:' + allLinks[i];
                                }
                            }
                        }

                        // 5. Variables JS globales
                        var jsVars = ['urlPlay', 'videoUrl', 'source', 'file', 'embedUrl', 'playerUrl', 'streamUrl', 'decodedUrl', 'finalUrl'];
                        for (var i = 0; i < jsVars.length; i++) {
                            try {
                                var val = eval(jsVars[i]);
                                if (typeof val === 'string' && val.indexOf('http') === 0) {
                                    return 'JSVAR:' + val;
                                }
                            } catch(e) {}
                        }

                        // 6. Debug: retornar info completa
                        var debugInfo = 'SCRIPTS:[' + (window.__tioplusScripts ? window.__tioplusScripts.join(' | ') : 'none') + ']';
                        debugInfo += ' TIMERS:[' + (window.__tioplusTimers ? window.__tioplusTimers.join(' | ') : 'none') + ']';
                        debugInfo += ' CAPTURED:[' + (window.__tioplusCaptured ? window.__tioplusCaptured.join(' | ') : 'none') + ']';
                        return 'DEBUG:' + debugInfo + ' HTML:' + html;
                    } catch(e) {
                        return 'ERROR:' + e.message;
                    }
                })()
            """.trimIndent()

            // Solo 2 intentos: uno corto y uno largo
            val delays = listOf(
                5000L to 3000L,
                10000L to 5000L
            )

            for ((waitMs, extraMs) in delays) {
                try {
                    val result = WebViewHelper.evaluate(
                        context = context,
                        url = playerUrl,
                        js = extractJs,
                        preloadJs = preloadJs,
                        waitForMs = waitMs,
                        extraJsWaitMs = extraMs
                    )

                    LogCollector.log("WEBVIEW", "TioPlus intento (${waitMs}ms): ${result.take(300)}")

                    // Log completo para debug
                    if (result.startsWith("DEBUG:")) {
                        val debugContent = result.removePrefix("DEBUG:")
                        LogCollector.log("DEBUG", "TioPlus debug info completo:")
                        // Log en chunks de 500 para no truncar
                        var offset = 0
                        while (offset < debugContent.length) {
                            LogCollector.log("DEBUG", "  [${offset}]: ${debugContent.substring(offset, minOf(offset + 500, debugContent.length))}")
                            offset += 500
                        }
                    }

                    val resolved = parseTioPlusResult(result, playerUrl)
                    if (resolved != null) return resolved
                } catch (e: Exception) {
                    LogCollector.log("WARN", "TioPlus intento fallo: ${e.message}")
                }
            }

            return null
        }

        private fun parseTioPlusResult(result: String, originalUrl: String): String? {
            return when {
                result.startsWith("CAPTURED:") -> {
                    val data = result.removePrefix("CAPTURED:")
                    val url = when {
                        data.startsWith("iframe:") -> data.removePrefix("iframe:")
                        data.startsWith("location:") -> data.removePrefix("location:")
                        else -> data
                    }
                    if (url.startsWith("http") && url != originalUrl) url else null
                }
                result.startsWith("CAPTURED_URL:") -> result.removePrefix("CAPTURED_URL:")
                result.startsWith("IFRAME:") -> result.removePrefix("IFRAME:")
                result.startsWith("LOCATION:") -> result.removePrefix("LOCATION:")
                result.startsWith("DIRECT:") -> result.removePrefix("DIRECT:")
                result.startsWith("EMBED:") -> result.removePrefix("EMBED:")
                result.startsWith("JSVAR:") -> result.removePrefix("JSVAR:")
                result.startsWith("http") -> result
                else -> null
            }
        }

        /**
         * Resuelve /player/ de TioPlus via HTTP (fallback sin WebView).
         */
        private suspend fun resolveTioPlusPlayerHttp(playerUrl: String): String {
            val html = try {
                HttpHelper.httpGet(playerUrl)
            } catch (e: Exception) {
                LogCollector.log("WARN", "HTTP fallo para /player/: ${e.message}")
                return playerUrl
            }

            // La pagina /player/ es simple: window.location.href = 'https://real-embed-url'
            // Con la doble codificacion correcta, esto deberia funcionar siempre.
            val locationHref = Regex("""window\.location\.href\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (!locationHref.isNullOrEmpty() && locationHref.startsWith("http")) {
                LogCollector.log("SUCCESS", "TioPlus /player/ resuelto: $locationHref")
                return locationHref
            }

            // Fallbacks por si el formato cambia
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (!iframeSrc.isNullOrEmpty() && iframeSrc.startsWith("http")) return iframeSrc

            val urlPlay = Regex("""var\s+urlPlay\s*=\s*["'](https?://[^"']+)["']""").find(html)?.groupValues?.get(1)
            if (!urlPlay.isNullOrEmpty() && urlPlay.startsWith("http")) return urlPlay

            // Buscar cualquier URL de embed conocida
            val embedDomains = listOf(
                "filemoon", "voe", "ok.ru", "vidguard", "vidsonic", "vidara",
                "rpmvid", "turboviplay", "emturbovid", "vidhideplus", "vidhide",
                "uqload", "doodstream", "earnvids", "upfast", "strp2p", "4meplayer", "upns"
            )
            val allUrls = Regex("""https?://[^"'<>\s]+""").findAll(html).map { it.value }.toList()
            for (url in allUrls) {
                for (domain in embedDomains) {
                    if (url.lowercase().contains(domain) && !url.contains("tioplus")) {
                        LogCollector.log("SUCCESS", "TioPlus /player/ dominio encontrado: $url")
                        return url
                    }
                }
            }

            LogCollector.log("WARN", "No se encontro URL real en /player/")
            return playerUrl
        }
    }
}

/**
 * HTTP helper compartido para todos los extractors.
 */
object HttpHelper {
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            LogCollector.log("REQUEST", "GET $url")
            val builder = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", UA)
            headers.forEach { (k, v) -> builder.header(k, v) }
            val resp = client.newCall(builder.build()).execute()
            val body = resp.body?.string() ?: throw Exception("Empty response")
            if (!resp.isSuccessful) {
                LogCollector.log("ERROR", "HTTP ${resp.code} from $url")
                throw Exception("HTTP ${resp.code}")
            }
            LogCollector.log("RESPONSE", "${resp.code} OK (${body.length} chars)")
            body
        }
    }

    suspend fun httpPost(url: String, headers: Map<String, String>, body: String): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            LogCollector.log("REQUEST", "POST $url")
            val builder = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", UA)
            headers.forEach { (k, v) -> builder.header(k, v) }
            val requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType())
            builder.post(requestBody)
            val resp = client.newCall(builder.build()).execute()
            val respBody = resp.body?.string() ?: throw Exception("Empty response")
            if (!resp.isSuccessful) {
                LogCollector.log("ERROR", "HTTP ${resp.code} from $url")
                throw Exception("HTTP ${resp.code}")
            }
            LogCollector.log("RESPONSE", "${resp.code} OK (${respBody.length} chars)")
            respBody
        }
    }
}
