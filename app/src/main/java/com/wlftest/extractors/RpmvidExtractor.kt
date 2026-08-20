package com.wlftest.extractors

import com.google.gson.JsonParser
import com.wlftest.ui.LogCollector
import java.net.URL
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Copiado de WlfMovie.
 * Aliases agregados: dtpg.rpmplay.xyz, dtpg2.rpmplay.xyz
 *
 * Original: usa Retrofit + Gson + DnsResolver
 * Adaptado: usa HttpHelper (OkHttp) + Gson
 */
class RpmvidExtractor : Extractor() {
    override val name = "Rpmvid"
    override val mainUrl = "https://rpmvid.com"
    override val aliasUrls = listOf(
        "https://cubeembed.rpmvid.com",
        "https://bummi.upns.xyz",
        "https://loadm.cam",
        "https://anibum.playerp2p.online",
        "https://pelisplus.upns.pro",
        "https://pelisplus.rpmstream.live",
        "https://pelisplus.strp2p.com",
        "https://flemmix.upns.pro",
        "https://moflix.rpmplay.xyz",
        "https://moflix.upns.xyz",
        "https://flix2day.xyz",
        "https://primevid.click",
        "https://totocoutouno.rpmlive.online",
        "https://dismoiceline.uns.bio",
        "https://doremifasol.ezplayer.me",
        "https://marcus.p2pstream.vip",
        "https://animeav1.uns.bio",
        // ALIASES NUEVOS para Gnula/TioPlus:
        "https://dtpg.rpmplay.xyz",
        "https://dtpg2.rpmplay.xyz",
    )

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
        private val KEY = "kiemtienmua911ca".toByteArray()
        private val IV = "1234567890oiuytr".toByteArray()
    }

    override suspend fun extract(link: String): Video {
        val id = extractId(link) ?: throw Exception("Invalid link: missing id after #")
        val mainLink = URL(link).protocol + "://" + URL(link).host
        val apiUrl = "$mainLink/api/v1/video?id=$id&w=1920&h=1080"

        val rawResponse = HttpHelper.httpGet(apiUrl, mapOf("Referer" to mainLink))

        // Intentar desencriptar como hex. Si falla, intentar parsear como JSON directo.
        val jsonStr = try {
            val decryptedJson = decryptHexPayload(rawResponse)
            LogCollector.log("DEBUG", "[Rpmvid] JSON desencriptado (${decryptedJson.length} chars)")
            decryptedJson
        } catch (_: Exception) {
            LogCollector.log("DEBUG", "[Rpmvid] Hex decrypt fallo, intentando JSON directo...")
            rawResponse
        }

        // Log completo en chunks de 500
        var off = 0
        while (off < jsonStr.length) {
            LogCollector.log("DEBUG", "[Rpmvid] JSON[${off}]: ${jsonStr.substring(off, minOf(off + 500, jsonStr.length))}")
            off += 500
        }

        val json = try {
            JsonParser.parseString(jsonStr).asJsonObject
        } catch (e: Exception) {
            throw Exception("No se pudo decodificar respuesta de Rpmvid: ${e.message}")
        }

        // Helper seguro para obtener string de un campo JSON
        // (evita crash cuando el valor es JsonObject/JsonArray en vez de String)
        fun safeGetString(obj: com.google.gson.JsonObject, field: String): String? {
            val elem = obj.get(field) ?: return null
            return if (elem.isJsonPrimitive) elem.asString else null
        }

        // Campos estandar del API Rpmvid
        val hlsPath = safeGetString(json, "hls")?.takeIf { it.isNotEmpty() }
        val hlsTiktok = safeGetString(json, "hlsVideoTiktok")?.takeIf { it.isNotEmpty() }
        var cfPath = safeGetString(json, "cf")?.takeIf { it.isNotEmpty() }
        val cfExpire = safeGetString(json, "cfExpire")?.takeIf { it.isNotEmpty() }

        // Campos alternativos que algunos mirrors pueden usar
        val altUrl = safeGetString(json, "url")?.takeIf { it.isNotEmpty() }
        val altSource = safeGetString(json, "source")?.takeIf { it.isNotEmpty() }
        val altFile = safeGetString(json, "file")?.takeIf { it.isNotEmpty() }
        val altVideoUrl = safeGetString(json, "videoUrl")?.takeIf { it.isNotEmpty() }
        val altM3u8 = safeGetString(json, "m3u8")?.takeIf { it.isNotEmpty() }
        val altStream = safeGetString(json, "stream")?.takeIf { it.isNotEmpty() }

        // Buscar paths de video DENTRO del streamingConfig
        // Algunos mirrors no tienen hls/hlsVideoTiktok/cf como campos top-level,
        // sino que la URL esta embebida en el streamingConfig
        var configUrl: String? = null
        val configStr = json.get("streamingConfig")?.asString
        if (!configStr.isNullOrEmpty()) {
            try {
                val config = JsonParser.parseString(configStr).asJsonObject
                val order = config.getAsJsonArray("order")
                val adjust = config.getAsJsonObject("adjust")

                // Iterar por orden de prioridad: Tiktok, Google, Cloudflare, In-House
                if (order != null && adjust != null) {
                    for (i in 0 until order.size()) {
                        val provider = order.get(i).asString
                        val providerConfig = adjust.getAsJsonObject(provider)
                        if (providerConfig == null) continue
                        val disabled = providerConfig.get("disabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                        if (disabled) continue

                        LogCollector.log("DEBUG", "[Rpmvid] Proveedor $provider habilitado, buscando URL...")

                        // Buscar campo 'path' o 'url' en el config del proveedor
                        val path = safeGetString(providerConfig, "path")?.takeIf { it.isNotEmpty() }
                        val pUrl = safeGetString(providerConfig, "url")?.takeIf { it.isNotEmpty() }
                        val hlsField = safeGetString(providerConfig, "hls")?.takeIf { it.isNotEmpty() }

                        if (!path.isNullOrEmpty() && path.startsWith("/")) {
                            configUrl = buildConfigUrl(mainLink, provider, path, providerConfig)
                            LogCollector.log("SUCCESS", "[Rpmvid] URL desde $provider.path: $configUrl")
                            break
                        }
                        if (!pUrl.isNullOrEmpty() && pUrl.startsWith("http")) {
                            configUrl = pUrl
                            LogCollector.log("SUCCESS", "[Rpmvid] URL desde $provider.url: $configUrl")
                            break
                        }
                        if (!hlsField.isNullOrEmpty() && hlsField.startsWith("/")) {
                            configUrl = buildConfigUrl(mainLink, provider, hlsField, providerConfig)
                            LogCollector.log("SUCCESS", "[Rpmvid] URL desde $provider.hls: $configUrl")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                LogCollector.log("WARN", "[Rpmvid] Error parseando streamingConfig: ${e.message}")
            }
        }

        // Buscar cualquier campo cuyo valor empiece con /hls/ o /cf/ o /video/
        if (configUrl == null) {
            val keys = json.keySet()
            for (key in keys) {
                val valStr = safeGetString(json, key) ?: continue
                if (valStr.startsWith("/hls/") || valStr.startsWith("/cf/") ||
                    valStr.startsWith("/video/") || valStr.startsWith("/stream/")) {
                    configUrl = "$mainLink$valStr"
                    LogCollector.log("SUCCESS", "[Rpmvid] Path encontrado en campo '$key': $configUrl")
                    break
                }
            }
        }

        val (finalUrl, headers) = when {
            !hlsPath.isNullOrEmpty() -> {
                "$mainLink$hlsPath" to mapOf("Referer" to mainLink)
            }
            !hlsTiktok.isNullOrEmpty() -> {
                var v = ""
                try {
                    val configStr = json.get("streamingConfig")?.asString
                    if (!configStr.isNullOrEmpty()) {
                        val config = JsonParser.parseString(configStr).asJsonObject
                        v = config.getAsJsonObject("adjust")
                            ?.getAsJsonObject("Tiktok")
                            ?.getAsJsonObject("params")
                            ?.get("v")?.asString ?: ""
                    }
                } catch (_: Exception) {}
                val query = if (v.isNotEmpty()) "?v=$v" else ""
                "$mainLink$hlsTiktok$query" to mapOf("Referer" to mainLink)
            }
            !cfPath.isNullOrEmpty() -> {
                var t: String? = null
                var e: String? = null
                val configStr = json.get("streamingConfig")?.asString
                try {
                    if (configStr != null) {
                        val streamingConfig = JsonParser.parseString(configStr).asJsonObject
                        val cloudflare = streamingConfig
                            .getAsJsonObject("adjust")
                            ?.getAsJsonObject("Cloudflare")
                        val disabled = cloudflare
                            ?.get("disabled")
                            ?.takeIf { !it.isJsonNull }
                            ?.asBoolean ?: true
                        if (!disabled) {
                            val params = cloudflare.getAsJsonObject("params")
                            t = params?.get("t")?.takeIf { !it.isJsonNull }?.asString
                            e = params?.get("e")?.takeIf { !it.isJsonNull }?.asString
                        }
                    }
                } catch (_: Exception) {}
                if (!e.isNullOrEmpty() && !t.isNullOrEmpty()) {
                    cfPath = "$cfPath?t=$t&e=$e"
                } else if (!cfExpire.isNullOrEmpty()) {
                    val parts = cfExpire.split("::")
                    if (parts.size >= 2) cfPath = "$cfPath?t=${parts[0]}&e=${parts[1]}"
                }
                cfPath!! to mapOf("Referer" to mainLink)
            }
            // Campos alternativos
            !altUrl.isNullOrEmpty() && altUrl.startsWith("http") -> {
                LogCollector.log("SUCCESS", "[Rpmvid] URL encontrada en campo 'url'")
                altUrl to mapOf("Referer" to mainLink)
            }
            !altSource.isNullOrEmpty() && altSource.startsWith("http") -> {
                LogCollector.log("SUCCESS", "[Rpmvid] URL encontrada en campo 'source'")
                altSource to mapOf("Referer" to mainLink)
            }
            !altFile.isNullOrEmpty() && altFile.startsWith("http") -> {
                LogCollector.log("SUCCESS", "[Rpmvid] URL encontrada en campo 'file'")
                altFile to mapOf("Referer" to mainLink)
            }
            !altVideoUrl.isNullOrEmpty() && altVideoUrl.startsWith("http") -> {
                LogCollector.log("SUCCESS", "[Rpmvid] URL encontrada en campo 'videoUrl'")
                altVideoUrl to mapOf("Referer" to mainLink)
            }
            !altM3u8.isNullOrEmpty() && altM3u8.startsWith("http") -> {
                LogCollector.log("SUCCESS", "[Rpmvid] URL encontrada en campo 'm3u8'")
                altM3u8 to mapOf("Referer" to mainLink)
            }
            !altStream.isNullOrEmpty() && altStream.startsWith("http") -> {
                LogCollector.log("SUCCESS", "[Rpmvid] URL encontrada en campo 'stream'")
                altStream to mapOf("Referer" to mainLink)
            }
            !configUrl.isNullOrEmpty() -> {
                configUrl to mapOf("Referer" to mainLink)
            }
            else -> throw Exception("Missing hls, hlsVideoTiktok, cf or url/source/file in response")
        }

        return Video(
            source = finalUrl,
            headers = headers,
            type = "application/x-mpegURL"
        )
    }

    private fun buildConfigUrl(mainLink: String, provider: String, path: String, providerConfig: com.google.gson.JsonObject): String {
        val baseUrl = when (provider) {
            "Tiktok" -> providerConfig.get("domain")?.asString ?: mainLink
            "Google" -> providerConfig.get("domain")?.asString ?: mainLink
            else -> mainLink
        }
        val paramsElem = providerConfig.get("params")
        val params = if (paramsElem != null && paramsElem.isJsonObject) paramsElem.asJsonObject else null
        var url = if (path.startsWith("http")) path else "$baseUrl$path"
        if (params != null) {
            val v = params.get("v")?.takeIf { !it.isJsonNull }?.asString
            val t = params.get("t")?.takeIf { !it.isJsonNull }?.asString
            val e = params.get("e")?.takeIf { !it.isJsonNull }?.asString
            val queryParts = mutableListOf<String>()
            if (!v.isNullOrEmpty()) queryParts.add("v=$v")
            if (!t.isNullOrEmpty()) queryParts.add("t=$t")
            if (!e.isNullOrEmpty()) queryParts.add("e=$e")
            if (queryParts.isNotEmpty()) url += "?" + queryParts.joinToString("&")
        }
        return url
    }

    private fun extractId(link: String): String? {
        val idx = link.indexOf('#')
        if (idx == -1 || idx == link.lastIndex) return null
        return link.substring(idx + 1).substringBefore("&")
    }

    private fun decryptHexPayload(hex: String): String {
        val bytes = hexToBytes(hex)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }

    private fun hexToBytes(input: String): ByteArray {
        val cleaned = input.lowercase(Locale.US).replace(Regex("[^0-9a-f]"), "")
        val even = if (cleaned.length % 2 == 0) cleaned else "0$cleaned"
        val out = ByteArray(even.length / 2)
        var i = 0
        var j = 0
        while (i < even.length) {
            out[j++] = ((even[i].digitToInt(16) shl 4) or even[i + 1].digitToInt(16)).toByte()
            i += 2
        }
        return out
    }
}
