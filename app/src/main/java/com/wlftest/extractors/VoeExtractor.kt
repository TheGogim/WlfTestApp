package com.wlftest.extractors

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wlftest.ui.LogCollector
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/**
 * Extractor para VOE.
 *
 * SOLUCIÓN 2025-08:
 *  Resolver el PoW de ALTCHA manualmente en Kotlin puro y enviar el payload
 *  por POST al form del gate. Esto bypassea completamente el WebView.
 *
 * Flujo:
 *  1. GET https://voe.sx/e/CODE → recibe JS redirect al dominio rotativo actual.
 *  2. GET https://<rotating-domain>/e/CODE → recibe HTML del gate con ALTCHA.
 *  3. GET https://<rotating-domain>/<challenge-path> → recibe JSON del challenge
 *     {parameters: {algorithm, cost, keyLength, keyPrefix, nonce, salt}, signature}
 *  4. Resolver PoW: iterar counter, PBKDF2-HMAC-SHA256(nonce||uint32_BE(counter), salt, cost, keyLength)
 *     hasta que el derivedKey empiece con keyPrefix (hex).
 *  5. Construir payload JSON: {challenge:{parameters,signature}, solution:{counter,derivedKey,time}}
 *  6. base64(JSON(payload)) → enviar como campo "altcha" en el POST al form action.
 *  7. El POST responde con la página real del video, que contiene el <script type="application/json">
 *     con la data encriptada.
 *  8. Aplicar DecryptHelper.decrypt() para obtener la URL del video.
 */
class VoeExtractor : Extractor() {
    override val name = "VOE"
    override val mainUrl = "https://voe.sx"
    override val needsWebView = false  // Ya no necesita WebView — resolvemos ALTCHA en Kotlin
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
        "https://juliewomanwish.com",
        // Dominios rotativos adicionales que VOE puede usar
        "https://rebeccapracticeloss.com",
    )

    /**
     * Punto de entrada principal.
     * No usa WebView — resuelve el ALTCHA PoW manualmente.
     */
    override suspend fun extract(link: String): Video {
        return solveViaAltchaPow(link)
    }

    /**
     * Compatibilidad con la API vieja que pide context.
     * Ignora el context y usa el flujo HTTP puro.
     */
    override suspend fun extractWithWebView(link: String, context: Context): Video {
        return extract(link)
    }

    /**
     * Resuelve el gate ALTCHA de VOE manualmente.
     */
    private suspend fun solveViaAltchaPow(embedUrl: String): Video {
        LogCollector.log("EXTRACTOR", "[VOE] Resolviendo ALTCHA PoW via HTTP...")

        // Cliente con cookies persistentes entre requests (necesario para que el
        // gate ALTCHA acepte el POST después del GET inicial).
        val cookieJar = InMemoryCookieJar()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .build()

        // 1. Seguir redirects JS manuales (voe.sx usa JS location.href, no HTTP 302)
        // Algunos dominios rotativos no tienen gate ALTCHA y entregan el video directo.
        var currentUrl = embedUrl
        var gateHtml: String? = null
        var gateUrl: String = currentUrl
        for (hop in 0 until 5) {
            val html = httpGetWithClient(client, currentUrl)
            LogCollector.log("EXTRACTOR", "[VOE] hop $hop: $currentUrl (${html.length} chars)")

            // Caso A: gate ALTCHA presente
            if (html.contains("altcha-widget") || html.contains("Confirm you")) {
                gateHtml = html
                gateUrl = currentUrl
                break
            }

            // Caso B: ya es la página final del video (tiene script app/json con data encriptada)
            // y NO tiene un form de gate (para distinguir de páginas intermedias).
            val hasAppJson = Regex(
                """<script[^>]+type="application/json"[^>]*>([^<]{200,})</script>""",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(html)
            val hasGateForm = html.contains("<form") && (html.contains("_token") || html.contains("altcha"))
            if (hasAppJson && !hasGateForm) {
                LogCollector.log("EXTRACTOR", "[VOE] Página final sin gate ALTCHA — extrayendo directo")
                return extractFromVideoPage(html, currentUrl)
            }

            // Buscar JS redirect: window.location.href = 'https://...'
            val jsRedirect = Regex("""window\.location\.href\s*=\s*'(https://[^']+)'""").find(html)?.groupValues?.get(1)
            if (jsRedirect != null && jsRedirect != currentUrl) {
                currentUrl = jsRedirect
                continue
            }

            // No gate, no redirect, no app/json — último intento: extraer lo que haya
            LogCollector.log("WARN", "[VOE] No se detectó gate, redirect ni script app/json en hop $hop")
            return extractFromVideoPage(html, currentUrl)
        }

        if (gateHtml == null) {
            throw Exception("VOE: no se encontró el gate ALTCHA después de seguir redirects")
        }

        // Log de cookies que tenemos hasta ahora
        LogCollector.log("DEBUG", "[VOE] Cookies después del GET del gate: ${cookieJar.debugCount()} cookies")

        // 2. Extraer del HTML del gate: _token, action URL, challenge URL
        val csrfToken = Regex("""name="_token"\s+value="([^"]+)"""").find(gateHtml)?.groupValues?.get(1)
            ?: throw Exception("VOE: _token no encontrado en gate")
        val actionUrl = Regex("""<form[^>]+action="([^"]+)"""").find(gateHtml)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?: gateUrl
        val challengeUrl = Regex("""<altcha-widget[^>]+challenge="([^"]+)"""").find(gateHtml)?.groupValues?.get(1)
            ?: throw Exception("VOE: challenge URL no encontrada en gate")

        LogCollector.log("EXTRACTOR", "[VOE] CSRF token: ${csrfToken.take(20)}...")
        LogCollector.log("EXTRACTOR", "[VOE] Challenge URL: $challengeUrl")
        LogCollector.log("EXTRACTOR", "[VOE] Action URL: $actionUrl")

        // 3. GET la URL del challenge → JSON con parámetros
        val challengeJsonStr = httpGetWithClient(client, challengeUrl)
        LogCollector.log("DEBUG", "[VOE] Cookies después del challenge GET: ${cookieJar.debugCount()} cookies")

        val challenge = JsonParser.parseString(challengeJsonStr).asJsonObject
        val params = challenge.getAsJsonObject("parameters")
        val signature = challenge.get("signature").asString

        val algorithm = params.get("algorithm").asString
        if (algorithm != "PBKDF2/SHA-256") {
            throw Exception("VOE: algoritmo ALTCHA no soportado: $algorithm")
        }
        val cost = params.get("cost").asInt
        val keyLength = params.get("keyLength")?.takeIf { !it.isJsonNull }?.asInt ?: 32
        val keyPrefix = params.get("keyPrefix").asString
        val nonce = params.get("nonce").asString
        val salt = params.get("salt").asString

        LogCollector.log("EXTRACTOR", "[VOE] ALTCHA params: cost=$cost keyPrefix=$keyPrefix keyLen=$keyLength")
        LogCollector.log("EXTRACTOR", "[VOE] nonce=${nonce.take(16)}... salt=${salt.take(16)}...")

        // 4. Resolver el PoW — en hilo de CPU (Dispatchers.Default) para NO bloquear la UI.
        // El PoW puede tardar 5-20s en Android (teléfonos son más lentos que desktop).
        val startMs = System.currentTimeMillis()
        val (counter, derivedKeyHex) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            solvePbkdf2Pow(nonce, salt, keyPrefix, cost, keyLength)
        }
        val tookMs = (System.currentTimeMillis() - startMs).toDouble()

        LogCollector.log("SUCCESS", "[VOE] PoW resuelto: counter=$counter derivedKey=${derivedKeyHex.take(16)}... time=${tookMs.toInt()}ms")

        // 5. Construir el payload (formato v2 — challenge sin campo _version)
        val payloadObj = JsonObject().apply {
            add("challenge", JsonObject().apply {
                add("parameters", params)
                addProperty("signature", signature)
            })
            add("solution", JsonObject().apply {
                addProperty("counter", counter)
                addProperty("derivedKey", derivedKeyHex)
                addProperty("time", tookMs)
            })
        }
        val payloadJson = payloadObj.toString()
        val payloadB64 = android.util.Base64.encodeToString(payloadJson.toByteArray(), android.util.Base64.NO_WRAP)

        LogCollector.log("DEBUG", "[VOE] Payload B64 length: ${payloadB64.length}, preview: ${payloadB64.take(60)}...")

        // 6. POST al form action con campo "altcha" (no "access")
        val formBody = okhttp3.FormBody.Builder()
            .add("_token", csrfToken)
            .add("altcha", payloadB64)
            .build()

        LogCollector.log("REQUEST", "[VOE] POST gate form: $actionUrl")
        LogCollector.log("DEBUG", "[VOE] Cookies antes del POST: ${cookieJar.debugCount()} cookies")

        val finalHtml = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val gateHost = runCatching { gateUrl.toHttpUrlOrNull()?.host }.getOrNull() ?: ""
                val postReq = okhttp3.Request.Builder()
                    .url(actionUrl)
                    .header("User-Agent", HttpHelper.UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Referer", gateUrl)
                    .header("Origin", "https://$gateHost")
                    .post(formBody)
                    .build()
                val postResp = client.newCall(postReq).execute()
                val respBody = postResp.body?.string() ?: ""
                LogCollector.log("RESPONSE", "[VOE] ${postResp.code} ${postResp.message} (${respBody.length} chars)")
                // Log primeros 300 chars para ver qué respondió el servidor
                LogCollector.log("DEBUG", "[VOE] POST response preview: ${respBody.take(300).replace("\n", " ")}")
                respBody
            }
        } catch (e: Exception) {
            // Capturar y loggear TODA la información de la excepción
            val errorClass = e.javaClass.name
            val errorMsg = e.message ?: "(null message)"
            val stackTop = e.stackTrace.take(5).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            LogCollector.log("ERROR", "[VOE] POST excepción: $errorClass: $errorMsg")
            LogCollector.log("ERROR", "[VOE] Stack: $stackTop")
            throw Exception("VOE: fallo en POST del gate: $errorClass: $errorMsg")
        }

        if (finalHtml.isEmpty()) {
            throw Exception("VOE: respuesta vacía del POST")
        }

        if (finalHtml.contains("altcha-widget") || finalHtml.contains("Confirm you")) {
            // El gate sigue presente — el servidor rechazó el payload
            throw Exception("VOE: el gate rechazó el payload ALTCHA (probablemente expiró el challenge o cookies inválidas)")
        }

        // 7. Extraer la URL del video de la página final
        return extractFromVideoPage(finalHtml, actionUrl)
    }

    /**
     * Dado el HTML de la página del video (post-gate), extrae la URL del video.
     * La data está encriptada en un <script type="application/json">.
     *
     * IMPORTANTE: el contenido del script puede ser:
     *   - Un string directo con la data encriptada (caso antiguo)
     *   - Un JSON array de un elemento: ["<data_encriptada>"] (caso nuevo 2025-08)
     *     que hay que unwrap antes de desencriptar.
     */
    private fun extractFromVideoPage(html: String, pageUrl: String): Video {
        LogCollector.log("DEBUG", "[VOE] Página final: ${html.length} chars. Buscando script app/json...")

        // Buscar el script type="application/json"
        val scriptMatch = Regex(
            """<script[^>]+type="application/json"[^>]*>([^<]+)</script>""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)

        val rawScriptContent: String = if (scriptMatch != null) {
            scriptMatch.groupValues[1].trim()
        } else {
            LogCollector.log("DEBUG", "[VOE] No se encontró script app/json. Probando fallback con patrones de VOE...")
            // Fallback: buscar scripts inline largos con patrones de VOE
            val inlineScripts = Jsoup.parse(html).select("script")
            var found: String? = null
            for (el in inlineScripts) {
                val text = el.data().trim()
                if (text.length > 200 && (text.contains("@$") || text.contains("^^") || text.contains("~@"))) {
                    found = text
                    break
                }
            }
            found ?: throw Exception("VOE: no se encontró script con data encriptada en la página final (HTML length: ${html.length})")
        }

        LogCollector.log("EXTRACTOR", "[VOE] Script app/json encontrado (${rawScriptContent.length} chars)")

        // Unwrap: si el contenido es un JSON array ["..."], extraer el elemento [0].
        // Si es un string JSON "..." normal, tomarlo.
        // Si no es JSON (data encriptada cruda), usarlo tal cual.
        val encodedString: String = unwrapVoeScriptContent(rawScriptContent)

        LogCollector.log("EXTRACTOR", "[VOE] Data encriptada final (${encodedString.length} chars)")

        // Desencriptar
        val decryptedContent: JsonObject = try {
            DecryptHelper.decrypt(encodedString)
        } catch (e: Exception) {
            LogCollector.log("WARN", "[VOE] DecryptHelper falló: ${e.message}, intentando parse directo...")
            try {
                JsonParser.parseString(encodedString).asJsonObject
            } catch (e2: Exception) {
                throw Exception("VOE: no se pudo desencriptar ni parsear la data: ${e2.message}")
            }
        }

        val videoUrl = decryptedContent.get("source")?.asString.orEmpty()
        if (videoUrl.isEmpty()) {
            // Debug: mostrar keys disponibles
            val keys = decryptedContent.keySet().joinToString(", ")
            throw Exception("VOE: 'source' vacío en JSON desencriptado. Keys disponibles: $keys")
        }

        // Subtítulos
        val subtitles = mutableListOf<Video.Subtitle>()
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

        LogCollector.log("SUCCESS", "[VOE] ${videoUrl.take(80)}...")
        return Video(source = videoUrl, subtitles = subtitles)
    }

    /**
     * VOE usa varios formatos para el script app/json:
     *
     * 1. String crudo (caso antiguo):  DQAk!!GUp8^^AIk4...
     *    → devolver tal cual
     *
     * 2. JSON array de 1 elemento:    ["DQAk!!GUp8^^AIk4..."]
     *    → devolver el elemento [0]
     *
     * 3. JSON string:                  "DQAk!!GUp8^^AIk4..."
     *    → devolver el contenido sin comillas
     *
     * El caso 2 es el actual (2025-08). El error 'Illegal base64 character 5b'
     * (0x5b = '[') aparecía porque el DecryptHelper recibía el array completo
     * en vez del elemento interno.
     */
    private fun unwrapVoeScriptContent(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed

        // Caso 2: JSON array ["..."]
        if (trimmed.startsWith("[")) {
            return try {
                val arr = JsonParser.parseString(trimmed).asJsonArray
                if (arr.size() > 0) {
                    arr.get(0).asString
                } else {
                    trimmed
                }
            } catch (_: Exception) {
                // Si no es JSON válido, intentar extraer el primer string entre comillas
                val m = Regex("""^["'\[]\s*["']([^"']+)["']\s*["'\]]?$""").find(trimmed)
                m?.groupValues?.getOrNull(1) ?: trimmed
            }
        }

        // Caso 3: JSON string "..." (con comillas dobles alrededor)
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
            return try {
                JsonParser.parseString(trimmed).asString
            } catch (_: Exception) {
                trimmed.substring(1, trimmed.length - 1)
            }
        }

        // Caso 1: string crudo
        return trimmed
    }

    /**
     * Resuelve el PoW PBKDF2/SHA-256 de ALTCHA.
     *
     * Encuentra el counter más pequeño tal que:
     *   password = nonce || uint32_BE(counter)
     *   derivedKey = PBKDF2-HMAC-SHA256(password, salt, iterations=cost, dkLen=keyLength)
     *   derivedKey empieza con keyPrefix (como bytes)
     */
    private fun solvePbkdf2Pow(
        nonceHex: String,
        saltHex: String,
        keyPrefixHex: String,
        cost: Int,
        keyLength: Int
    ): Pair<Int, String> {
        val nonceBytes = hexToBytes(nonceHex)
        val saltBytes = hexToBytes(saltHex)
        val keyPrefixBytes = hexToBytes(keyPrefixHex)
        val prefixLen = keyPrefixBytes.size

        var counter = 0
        val maxIter = 10_000_000  // safety limit
        while (counter < maxIter) {
            // password = nonce || uint32_BE(counter) (4 bytes, big endian)
            val password = ByteArray(nonceBytes.size + 4)
            System.arraycopy(nonceBytes, 0, password, 0, nonceBytes.size)
            password[nonceBytes.size]     = (counter ushr 24).toByte()
            password[nonceBytes.size + 1] = (counter ushr 16).toByte()
            password[nonceBytes.size + 2] = (counter ushr 8).toByte()
            password[nonceBytes.size + 3] = counter.toByte()

            val derivedKey = pbkdf2HmacSha256(password, saltBytes, cost, keyLength)
            if (derivedKey.size >= prefixLen && derivedKey.copyOfRange(0, prefixLen).contentEquals(keyPrefixBytes)) {
                return Pair(counter, bytesToHex(derivedKey))
            }
            counter++
        }
        throw Exception("VOE: PoW no resuelto después de $maxIter iteraciones")
    }

    /**
     * PBKDF2-HMAC-SHA256 manual (porque el provider estándar de Android solo
     * acepta char[] passwords, no bytes).
     */
    private fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength  // 32 for SHA-256
        val blocks = (dkLen + hLen - 1) / hLen
        val result = ByteArray(dkLen)
        for (blockIndex in 1..blocks) {
            // F(P, S, c, i) = U1 ^ U2 ^ ... ^ Uc
            // U1 = HMAC(P, S || INT_32_BE(i))
            val saltWithBlock = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, saltWithBlock, 0, salt.size)
            saltWithBlock[salt.size]     = (blockIndex ushr 24).toByte()
            saltWithBlock[salt.size + 1] = (blockIndex ushr 16).toByte()
            saltWithBlock[salt.size + 2] = (blockIndex ushr 8).toByte()
            saltWithBlock[salt.size + 3] = blockIndex.toByte()

            var u = mac.doFinal(saltWithBlock)
            val t = u.copyOf()
            for (j in 1 until iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) {
                    t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
            }
            val offset = (blockIndex - 1) * hLen
            val len = minOf(hLen, dkLen - offset)
            System.arraycopy(t, 0, result, offset, len)
        }
        return result
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.lowercase().replace(Regex("[^0-9a-f]"), "")
        val padded = if (clean.length % 2 == 0) clean else "0$clean"
        val out = ByteArray(padded.length / 2)
        for (i in padded.indices step 2) {
            out[i / 2] = ((Character.digit(padded[i], 16) shl 4) or Character.digit(padded[i + 1], 16)).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /**
     * HTTP GET con cliente custom (para mantener cookies).
     */
    private suspend fun httpGetWithClient(client: okhttp3.OkHttpClient, url: String): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", HttpHelper.UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: throw Exception("VOE: respuesta vacía de $url")
            if (!resp.isSuccessful) {
                throw Exception("VOE: HTTP ${resp.code} de $url")
            }
            body
        }
    }
}

/**
 * CookieJar simple que mantiene cookies en memoria por host.
 * Necesario para el flujo de VOE donde el POST debe enviar las cookies
 * que se setearon en el GET inicial (CSRF, sesión, etc.).
 */
private class InMemoryCookieJar : okhttp3.CookieJar {
    private val cookiesByHost = mutableMapOf<String, MutableList<okhttp3.Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        val now = System.currentTimeMillis()
        val existing = cookiesByHost[url.host].orEmpty().toMutableList()
        // Eliminar cookies con el mismo nombre+path (reemplazar por las nuevas)
        for (newCookie in cookies) {
            existing.removeAll { it.name == newCookie.name && it.path == newCookie.path }
            if (newCookie.expiresAt > now) {
                existing.add(newCookie)
            }
        }
        cookiesByHost[url.host] = existing
    }

    @Synchronized
    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
        val now = System.currentTimeMillis()
        return cookiesByHost[url.host].orEmpty().filter { it.expiresAt > now }
    }

    /**
     * Para debug: devuelve el número de cookies almacenadas para un host (o todos si host es null).
     */
    @Synchronized
    fun debugCount(host: String? = null): Int {
        return if (host != null) {
            cookiesByHost[host]?.size ?: 0
        } else {
            cookiesByHost.values.sumOf { it.size }
        }
    }
}
