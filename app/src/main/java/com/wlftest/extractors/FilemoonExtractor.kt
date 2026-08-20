package com.wlftest.extractors

import android.content.Context
import android.util.Base64
import com.wlftest.ui.LogCollector
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Extractor para Filemoon y sus dominios alias.
 *
 * Estrategia 2025-08:
 * 1. Intentar API directa (details -> challenge -> attest -> playback)
 * 2. Si el API falla (ej. HTTP 400 en attest), usar WebView para que
 *    el navegador maneje la atestacion nativamente y capturar la URL.
 */
class FilemoonExtractor : Extractor() {

    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.site"
    override val aliasUrls = listOf(
        "https://bf0skv.org",
        "https://bysejikuar.com",
        "https://moflix-stream.link",
        "https://bysezoxexe.com",
        "https://bysebuho.com",
        "https://filemoon.sx",
        "https://bysekoze.com",
        "https://bysesayeveum.com",
        "https://bysevepoin.com",
    )
    override val needsWebView = true

    private var deviceId = UUID.randomUUID().toString().replace("-", "")

    override suspend fun extract(link: String): Video {
        throw Exception("Filemoon requiere WebView. Usar extractWithWebView().")
    }

    /**
     * Punto de entrada principal.
     * 1. Resolver el embed URL real (wrapper -> embed_frame_url)
     * 2. Intentar API en el dominio ORIGINAL (donde details funciona)
     * 3. Si falla, intentar API en el dominio del embed
     * 4. Si falla, cargar el EMBED en WebView (no el wrapper)
     */
    override suspend fun extractWithWebView(link: String, context: Context): Video {
        // Paso 1: Resolver al embed URL real
        val embedUrl = resolveToEmbedUrl(link)
        LogCollector.log("EXTRACTOR", "[Filemoon] Embed URL: $embedUrl")

        // Paso 2: Intentar API en dominio ORIGINAL (mas confiable)
        try {
            return extractViaApi(link, embedUrl)
        } catch (e: Exception) {
            LogCollector.log("WARN", "[Filemoon] API original fallo: ${e.message}")
        }

        // Paso 3: Intentar API en dominio del embed
        if (embedUrl != link) {
            try {
                return extractViaApi(embedUrl, embedUrl)
            } catch (e: Exception) {
                LogCollector.log("WARN", "[Filemoon] API embed fallo: ${e.message}")
            }
        }

        // Paso 4: WebView carga el EMBED real (no el wrapper)
        return extractViaWebView(embedUrl, context)
    }

    /**
     * Resuelve una URL cualquiera al embed URL real de Filemoon.
     * Puede ser un wrapper con iframe, o una URL /e/ que necesita
     * consultar /api/videos/ID/embed/details para obtener el embed_frame_url.
     */
    private suspend fun resolveToEmbedUrl(link: String): String {
        // 1. Si ya es un embed URL con path tipo /g4jas/, /lmz/, etc. usarla directamente
        val pathMatch = Regex("""/([a-z0-9]+)/([a-zA-Z0-9]+)$""").find(link)
        if (pathMatch != null) {
            val pathType = pathMatch.groupValues[1]
            // /e/ es el wrapper, los demas son embeds reales
            if (pathType != "e" && pathType != "d" && pathType != "s8h4p") {
                LogCollector.log("EXTRACTOR", "[Filemoon] URL ya es embed real: $link")
                return link
            }
        }

        // 2. Intentar resolver wrapper con iframe
        try {
            val html = HttpHelper.httpGet(link)
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"' ]+)["']""").find(html)?.groupValues?.get(1)
            if (iframeSrc != null && iframeSrc != link) {
                LogCollector.log("EXTRACTOR", "[Filemoon] Wrapper detectado, iframe real: $iframeSrc")
                return iframeSrc
            }
        } catch (_: Exception) {}

        // 3. Consultar details API para obtener embed_frame_url
        val matcher = Regex("""/(e|d|s8h4p)/([a-zA-Z0-9]+)""").find(link)
        if (matcher != null) {
            val videoId = matcher.groupValues[2]
            val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1) ?: return link
            try {
                val detailsUrl = "$currentDomain/api/videos/$videoId/embed/details"
                val detailsJson = HttpHelper.httpGet(detailsUrl)
                val detailsObj = JSONObject(detailsJson)
                val embedFrameUrl = tryExtractEmbedUrl(detailsObj, link)
                if (embedFrameUrl != null) {
                    LogCollector.log("EXTRACTOR", "[Filemoon] Embed URL desde details API: $embedFrameUrl")
                    return embedFrameUrl
                }
            } catch (e: Exception) {
                LogCollector.log("WARN", "[Filemoon] Details API fallo: ${e.message}")
            }
        }

        return link
    }

    /**
     * Metodo API: details -> challenge -> attest -> playback -> decrypt.
     * Puede fallar con HTTP 400 si el formato de atestacion cambio.
     */
    private suspend fun extractViaApi(requestUrl: String, embedFrameUrl: String): Video {
        val matcher = Regex("""/([a-zA-Z0-9]+)/([a-zA-Z0-9]+)$""").find(embedFrameUrl)
            ?: throw Exception("Could not extract video ID from Filemoon URL")
        val videoId = matcher.groupValues[2]
        val apiDomain = Regex("""(https?://[^/]+)""").find(requestUrl)?.groupValues?.get(1)
            ?: throw Exception("Could not extract base URL")

        LogCollector.log("EXTRACTOR", "[Filemoon] API: domain=$apiDomain | embed=$embedFrameUrl | ID: $videoId")

        // 1. Details
        val detailsUrl = "$apiDomain/api/videos/$videoId/embed/details"
        val detailsJson = HttpHelper.httpGet(detailsUrl)
        LogCollector.log("RESPONSE", "[Filemoon] Details: ${detailsJson.take(300)}")

        val detailsObj = JSONObject(detailsJson)
        val resolvedEmbedUrl = tryExtractEmbedUrl(detailsObj, embedFrameUrl)
            ?: throw Exception("No se encontro embed URL en details")

        val playbackDomain = Regex("""(https?://[^/]+)""").find(resolvedEmbedUrl)?.groupValues?.get(1)
            ?: apiDomain

        LogCollector.log("EXTRACTOR", "[Filemoon] embed: $resolvedEmbedUrl | playback: $playbackDomain")

        // 2. Challenge (usar el dominio de playback para challenge/attest/playback)
        val challengeUrl = "$playbackDomain/api/videos/access/challenge"
        val challengeHeaders = mapOf(
            "Referer" to resolvedEmbedUrl,
            "Origin" to playbackDomain,
            "User-Agent" to HttpHelper.UA
        )
        val challengeJson = HttpHelper.httpPost(challengeUrl, challengeHeaders, "")
        val challengeObj = JSONObject(challengeJson)
        val challengeId = challengeObj.getString("challenge_id")
        val nonce = challengeObj.getString("nonce")
        val viewerId = UUID.randomUUID().toString().replace("-", "")

        // 3. Attestation
        val attestation = generateAttestation(nonce)
        val attestUrl = "$playbackDomain/api/videos/access/attest"
        val attestPayload = JSONObject().apply {
            put("viewer_id", viewerId)
            put("device_id", deviceId)
            put("challenge_id", challengeId)
            put("nonce", nonce)
            put("signature", attestation.signature)
            put("public_key", attestation.publicKey)
            put("client", JSONObject().apply {
                put("user_agent", HttpHelper.UA)
                put("architecture", "x86")
                put("bitness", 64)
                put("platform", "Windows")
                put("platform_version", "10.0.0")
                put("pixel_ratio", 1.0)
                put("screen_width", 1920)
                put("screen_height", 1080)
                put("languages", "[\"en-US\"]")
            })
            put("storage", JSONObject().apply {
                put("cookie", viewerId)
                put("local_storage", viewerId)
                put("indexed_db", "$viewerId:$deviceId")
                put("cache_storage", "$viewerId:$deviceId")
            })
            put("attributes", JSONObject().apply { put("entropy", "high") })
        }
        val attestHeaders = mapOf(
            "Referer" to resolvedEmbedUrl,
            "Origin" to playbackDomain,
            "User-Agent" to HttpHelper.UA,
            "Content-Type" to "application/json",
        )
        val attestResponse = HttpHelper.httpPost(attestUrl, attestHeaders, attestPayload.toString())
        val attestObj = JSONObject(attestResponse)
        val token = attestObj.getString("token")
        val confidence = attestObj.getDouble("confidence")

        LogCollector.log("EXTRACTOR", "[Filemoon] Token obtenido (confidence: $confidence)")

        // 4. Playback
        val playbackUrl = "$playbackDomain/api/videos/$videoId/embed/playback"
        val playbackPayload = JSONObject().apply {
            put("fingerprint", JSONObject().apply {
                put("token", token)
                put("viewer_id", viewerId)
                put("device_id", deviceId)
                put("confidence", confidence)
            })
        }
        val playbackHeaders = mapOf(
            "Referer" to resolvedEmbedUrl,
            "Origin" to playbackDomain,
            "X-Embed-Parent" to requestUrl,
            "User-Agent" to HttpHelper.UA,
            "Content-Type" to "application/json",
        )
        val playbackResponse = HttpHelper.httpPost(playbackUrl, playbackHeaders, playbackPayload.toString())
        val playbackObj = JSONObject(playbackResponse)
        val playbackData = playbackObj.getJSONObject("playback")

        // 5. Decrypt
        val decryptedJson = decryptPlayback(playbackData)
        val sourcesObj = JSONObject(decryptedJson).getJSONArray("sources")
        val sourceUrl = sourcesObj.getJSONObject(0).getString("url")

        LogCollector.log("SUCCESS", "[Filemoon] API Source: ${sourceUrl.take(80)}...")
        return Video(
            source = sourceUrl,
            type = if (sourceUrl.contains(".m3u8")) "application/x-mpegURL" else "video/mp4",
            headers = mapOf(
                "Referer" to resolvedEmbedUrl,
                "User-Agent" to HttpHelper.UA,
                "Origin" to playbackDomain
            )
        )
    }

    /**
     * WebView approach: carga el embed y deja que el JS del sitio
     * maneje challenge/attest nativamente, capturando la URL de video.
     */
    private suspend fun extractViaWebView(embedUrl: String, context: Context): Video {
        LogCollector.log("WEBVIEW", "[Filemoon] Cargando embed via WebView...")

        val preloadJs = """
            (function() {
                window.__fmUrls = [];
                window.__fmAllRequests = [];
                window.__fmVideoSrc = null;

                // Interceptar XHR para capturar TODAS las respuestas relevantes
                var origXHROpen = XMLHttpRequest.prototype.open;
                var origXHRSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__fmUrl = url;
                    return origXHROpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function(body) {
                    var self = this;
                    this.addEventListener('load', function() {
                        var url = self.__fmUrl || '';
                        window.__fmAllRequests.push(url);
                        if ((url.indexOf('/api/') !== -1 || url.indexOf('playback') !== -1 || url.indexOf('m3u8') !== -1 || url.indexOf('.mp4') !== -1) && self.responseText) {
                            window.__fmUrls.push('API:' + url + ' -> ' + self.responseText.substring(0, 2000));
                        }
                    });
                    return origXHRSend.apply(this, arguments);
                };

                // Interceptar fetch
                if (window.fetch) {
                    var origFetch = window.fetch.bind(window);
                    window.fetch = function(input) {
                        var inputUrl = (typeof input === 'string') ? input : (input.url || '');
                        window.__fmAllRequests.push(inputUrl);
                        return origFetch.apply(this, arguments).then(function(resp) {
                            if ((inputUrl.indexOf('/api/') !== -1 || inputUrl.indexOf('playback') !== -1 || inputUrl.indexOf('m3u8') !== -1 || inputUrl.indexOf('.mp4') !== -1)) {
                                var cloned = resp.clone();
                                cloned.text().then(function(text) {
                                    if (text) window.__fmUrls.push('API:' + inputUrl + ' -> ' + text.substring(0, 2000));
                                });
                            }
                            return resp;
                        });
                    };
                }

                // Interceptar setAttribute en elementos para capturar src de video
                var origSetAttr = Element.prototype.setAttribute;
                Element.prototype.setAttribute = function(name, value) {
                    if ((name === 'src' || name === 'data-src') && value && value.indexOf('http') === 0 && (value.indexOf('.m3u8') !== -1 || value.indexOf('.mp4') !== -1)) {
                        window.__fmVideoSrc = value;
                    }
                    return origSetAttr.apply(this, arguments);
                };

                // MutationObserver para detectar video elements agregados al DOM
                var observer = new MutationObserver(function(mutations) {
                    for (var m = 0; m < mutations.length; m++) {
                        for (var n = 0; n < mutations[m].addedNodes.length; n++) {
                            var node = mutations[m].addedNodes[n];
                            if (node.tagName === 'VIDEO') {
                                var src = node.src || node.currentSrc || '';
                                if (src && src.indexOf('http') === 0) window.__fmVideoSrc = src;
                                var sources = node.querySelectorAll('source');
                                for (var s = 0; s < sources.length; s++) {
                                    src = sources[s].src || sources[s].getAttribute('src') || '';
                                    if (src && src.indexOf('http') === 0) window.__fmVideoSrc = src;
                                }
                            }
                            if (node.tagName === 'SOURCE') {
                                var src2 = node.src || node.getAttribute('src') || '';
                                if (src2 && src2.indexOf('http') === 0) window.__fmVideoSrc = src2;
                            }
                        }
                    }
                });
                if (document.body) {
                    observer.observe(document.body, {childList: true, subtree: true});
                } else {
                    document.addEventListener('DOMContentLoaded', function() {
                        observer.observe(document.body, {childList: true, subtree: true});
                    });
                }
            })();
        """.trimIndent()

        val extractJs = """
            (function() {
                try {
                    // 0. Verificar si React SPA cargo o sigue en placeholder
                    var placeholder = document.querySelector('.video-page__placeholder');
                    var isReactMounted = placeholder && placeholder.children.length > 0;
                    var rootContent = document.getElementById('root');
                    var rootHtmlLen = rootContent ? rootContent.innerHTML.length : 0;

                    // 1. Revisar si capturamos respuestas API
                    if (window.__fmUrls && window.__fmUrls.length > 0) {
                        for (var i = 0; i < window.__fmUrls.length; i++) {
                            var data = window.__fmUrls[i];
                            var urls = data.match(/https?:\/\/[^"'<>\s]+\.(?:m3u8|mp4)[^"'<>\s]*/g);
                            if (urls && urls.length > 0) {
                                return 'URL:' + urls[0];
                            }
                            // Buscar en JSON capturado campos sources/file/url
                            var srcMatch = data.match(/"(?:sources|file|url)"\s*:\s*"(https?:\/\/[^"\\]+)"/);
                            if (srcMatch) return 'URL:' + srcMatch[1];
                        }
                    }

                    // 1b. Video src capturado por MutationObserver/setAttribute
                    if (window.__fmVideoSrc && window.__fmVideoSrc.indexOf('http') === 0) {
                        return 'URL:' + window.__fmVideoSrc;
                    }

                    // 2. JWPlayer
                    try {
                        if (typeof jwplayer !== 'undefined' && jwplayer()) {
                            var pl = jwplayer().getPlaylist();
                            if (pl && pl.length > 0) {
                                if (pl[0].file && pl[0].file.indexOf('http') === 0) return 'URL:' + pl[0].file;
                                if (pl[0].sources) {
                                    for (var i = 0; i < pl[0].sources.length; i++) {
                                        var f = pl[0].sources[i].file || '';
                                        if (f.indexOf('http') === 0 && (f.indexOf('.m3u8') !== -1 || f.indexOf('.mp4') !== -1)) {
                                            return 'URL:' + f;
                                        }
                                    }
                                }
                            }
                        }
                    } catch(e) {}

                    // 3. Video element + source children
                    var video = document.querySelector('video');
                    if (video) {
                        var src = video.src || video.currentSrc || '';
                        if (src && src.indexOf('http') === 0) return 'URL:' + src;
                        var sources = video.querySelectorAll('source');
                        for (var s = 0; s < sources.length; s++) {
                            src = sources[s].src || sources[s].getAttribute('src') || '';
                            if (src && src.indexOf('http') === 0) return 'URL:' + src;
                        }
                    }

                    // 4. HTML scan
                    var html = document.documentElement.innerHTML;
                    var m3u8 = html.match(/https?:\/\/[^"'<>\s]+\.m3u8[^"'<>\s]*/);
                    if (m3u8) return 'URL:' + m3u8[0];
                    var mp4 = html.match(/https?:\/\/[^"'<>\s]+\.mp4[^"'<>\s]*/);
                    if (mp4) return 'URL:' + mp4[0];

                    // 5. Buscar en scripts inline
                    var scripts = document.querySelectorAll('script');
                    for (var i = 0; i < scripts.length; i++) {
                        var text = scripts[i].textContent || '';
                        var fMatch = text.match(/["']file["']\s*:\s*["'](https?:\/\/[^"' ]+)["']/);
                        if (fMatch) return 'URL:' + fMatch[1];
                    }

                    // 6. Debug con info de estado
                    var reqCount = window.__fmAllRequests ? window.__fmAllRequests.length : 0;
                    var reqSnippet = '';
                    if (window.__fmAllRequests && window.__fmAllRequests.length > 0) {
                        reqSnippet = ' reqs=[' + window.__fmAllRequests.slice(0, 8).join(',') + ']';
                    }
                    // Log primeros 300 chars del HTML para diagnostico
                    var htmlSnippet = html.substring(0, 300).replace(/\n/g, ' ');
                    return 'WAITING:scripts=' + scripts.length +
                           ' captured=' + (window.__fmUrls ? window.__fmUrls.length : 0) +
                           ' total_reqs=' + reqCount +
                           ' html_len=' + html.length +
                           ' react_mounted=' + isReactMounted +
                           ' root_len=' + rootHtmlLen +
                           ' video=' + (video ? 'YES' : 'NO') +
                           reqSnippet +
                           ' snippet=' + htmlSnippet;
                } catch(e) {
                    return 'ERROR:' + e.message;
                }
            })();
        """.trimIndent()

        val attempts = listOf(
            10000L to 5000L,
            18000L to 5000L,
            26000L to 4000L
        )

        for ((waitMs, extraMs) in attempts) {
            try {
                val result = WebViewHelper.evaluate(
                    context = context,
                    url = embedUrl,
                    js = extractJs,
                    preloadJs = preloadJs,
                    waitForMs = waitMs,
                    extraJsWaitMs = extraMs
                )

                LogCollector.log("WEBVIEW", "[Filemoon] intento (${waitMs}ms): ${result.take(200)}")

                if (result.startsWith("URL:")) {
                    val videoUrl = result.removePrefix("URL:")
                    LogCollector.log("SUCCESS", "[Filemoon] WebView URL: ${videoUrl.take(80)}...")
                    return Video(
                        source = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) "application/x-mpegURL" else "video/mp4",
                        headers = mapOf("Referer" to embedUrl, "User-Agent" to HttpHelper.UA)
                    )
                }

                if (result.startsWith("PLAYBACK:")) {
                    LogCollector.log("DEBUG", "[Filemoon] Playback data capturado: ${result.take(500)}")
                }
            } catch (e: Exception) {
                LogCollector.log("WARN", "[Filemoon] WebView intento fallo: ${e.message}")
            }
        }

        // Ultimo recurso: HTTP fallback scan
        return extractHttpFallback(embedUrl)
    }

    /**
     * Fallback HTTP: escanear HTML buscando URLs de video.
     */
    private suspend fun extractHttpFallback(embedUrl: String): Video {
        LogCollector.log("EXTRACTOR", "[Filemoon] Ultimo recurso: HTTP scan...")
        val html = HttpHelper.httpGet(embedUrl)

        val mp4Regex = Regex("""(https://[^"'<>\s]+\.(?:mp4|m3u8)[^"'<>\s]*)""")
        val directUrl = mp4Regex.find(html)?.groupValues?.get(1)
        if (directUrl != null) {
            LogCollector.log("SUCCESS", "[Filemoon] URL directa (fallback): ${directUrl.take(80)}...")
            return Video(
                source = directUrl,
                type = if (directUrl.contains(".m3u8")) "application/x-mpegURL" else "video/mp4",
                headers = mapOf("Referer" to embedUrl, "User-Agent" to HttpHelper.UA)
            )
        }

        val jwConfig = Regex("""file\s*:\s*['"](https://[^'"<>]+)['"]""").find(html)?.groupValues?.get(1)
        if (jwConfig != null) {
            LogCollector.log("SUCCESS", "[Filemoon] JWPlayer (fallback): ${jwConfig.take(80)}...")
            return Video(
                source = jwConfig,
                type = if (jwConfig.contains(".m3u8")) "application/x-mpegURL" else "video/mp4",
                headers = mapOf("Referer" to embedUrl, "User-Agent" to HttpHelper.UA)
            )
        }

        throw Exception("Filemoon: todos los metodos fallaron para $embedUrl")
    }

    /**
     * Extrae la URL del embed del JSON de details.
     */
    private fun tryExtractEmbedUrl(detailsObj: JSONObject, fallbackUrl: String): String? {
        val possibleFields = listOf(
            "embed_frame_url", "embedUrl", "embed_url",
            "embed", "player_url", "playerUrl", "url"
        )
        for (field in possibleFields) {
            val value = detailsObj.optString(field, "")
            if (value.startsWith("http")) {
                LogCollector.log("EXTRACTOR", "[Filemoon] Embed URL en campo '$field': $value")
                return value
            }
        }

        val keys = detailsObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = detailsObj.optString(key, "")
            if (value.startsWith("http") && (value.contains("/e/") || value.contains("/embed") || value.contains("/s8h4p/"))) {
                LogCollector.log("EXTRACTOR", "[Filemoon] Embed URL en campo '$key': $value")
                return value
            }
        }

        val host = detailsObj.optString("host", "")
        val cdn = detailsObj.optString("cdn", "")
        if (host.startsWith("http")) {
            val embedPath = Regex("""/[^/]+$""").find(fallbackUrl)?.value ?: "/e/"
            return host + embedPath
        }
        if (cdn.startsWith("http")) {
            val embedPath = Regex("""/[^/]+$""").find(fallbackUrl)?.value ?: "/e/"
            return cdn + embedPath
        }

        return null
    }

    // --- Attestation (ECDSA P-256) ---

    private data class Attestation(val signature: String, val publicKey: JSONObject)

    private fun generateAttestation(nonce: String): Attestation {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val pub = kp.public as ECPublicKey

        val x = Base64.encodeToString(pub.w.affineX.toByteArray().stripLeadingZero(), Base64.URL_SAFE or Base64.NO_WRAP)
            .replace("=", "")
        val y = Base64.encodeToString(pub.w.affineY.toByteArray().stripLeadingZero(), Base64.URL_SAFE or Base64.NO_WRAP)
            .replace("=", "")

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(kp.private)
        sig.update(nonce.toByteArray())
        val rawSig = derToRaw(sig.sign())
        val encodedSig = Base64.encodeToString(rawSig, Base64.URL_SAFE or Base64.NO_WRAP)
            .replace("=", "")

        val jwk = JSONObject().apply {
            put("crv", "P-256")
            put("ext", true)
            put("key_ops", listOf("verify"))
            put("kty", "EC")
            put("x", x)
            put("y", y)
        }
        return Attestation(encodedSig, jwk)
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var offset = 2
        val rLen = der[offset + 1].toInt()
        val r = der.copyOfRange(offset + 2, offset + 2 + rLen).stripLeadingZero()
        offset += 2 + rLen
        val sLen = der[offset + 1].toInt()
        val s = der.copyOfRange(offset + 2, offset + 2 + sLen).stripLeadingZero()
        val raw = ByteArray(64)
        System.arraycopy(r, 0, raw, 32 - r.size, r.size)
        System.arraycopy(s, 0, raw, 64 - s.size, s.size)
        return raw
    }

    private fun ByteArray.stripLeadingZero(): ByteArray =
        if (isNotEmpty() && this[0] == 0.toByte()) copyOfRange(1, size) else this

    // --- AES-GCM decrypt ---

    private fun decryptPlayback(data: JSONObject): String {
        val iv = Base64.decode(data.getString("iv"), Base64.URL_SAFE or Base64.NO_WRAP)
        val payload = Base64.decode(data.getString("payload"), Base64.URL_SAFE or Base64.NO_WRAP)
        val keyParts = data.getJSONArray("key_parts")
        val p1 = Base64.decode(keyParts.getString(0), Base64.URL_SAFE or Base64.NO_WRAP)
        val p2 = Base64.decode(keyParts.getString(1), Base64.URL_SAFE or Base64.NO_WRAP)
        val key = ByteArray(p1.size + p2.size)
        System.arraycopy(p1, 0, key, 0, p1.size)
        System.arraycopy(p2, 0, key, p1.size, p2.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }
}
