package com.wlftest.extractors

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.wlftest.ui.LogCollector
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Utilidad para ejecutar JavaScript en un WebView invisible.
 * Usado por extractors que necesitan renderizado JS (Okru, TioPlus player).
 */
object WebViewHelper {

    /**
     * Carga una URL en un WebView invisible, espera a que cargue,
     * ejecuta JavaScript y retorna el resultado.
     *
     * @param context Application context
     * @param url URL a cargar
     * @param js JavaScript a ejecutar despues de la carga
     * @param preloadJs JavaScript a inyectar ANTES de que la pagina ejecute sus scripts (en onPageStarted)
     * @param waitForMs milisegundos extra a esperar despues de onPageFinished
     * @param redirectCapture si true, captura la primera redirect como resultado
     * @param extraJsWaitMs milisegundos adicionales antes de reintentar el JS
     * @return resultado del JS o la URL de redirect capturada
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun evaluate(
        context: Context,
        url: String,
        js: String = "(function(){ return document.documentElement.outerHTML; })()",
        preloadJs: String? = null,
        waitForMs: Long = 2000,
        redirectCapture: Boolean = false,
        extraJsWaitMs: Long = 2000
    ): String {
        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                try {
                    val webView = WebView(context.applicationContext)
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.loadWithOverviewMode = true
                    webView.settings.useWideViewPort = true
                    webView.settings.userAgentString = HttpHelper.UA

                    var finished = false
                    fun finish(result: String) {
                        if (finished) return
                        finished = true
                        handler.post {
                            try { webView.destroy() } catch (_: Exception) {}
                            if (cont.isActive) cont.resume(result)
                        }
                    }

                    fun fail(error: Throwable) {
                        if (finished) return
                        finished = true
                        handler.post {
                            try { webView.destroy() } catch (_: Exception) {}
                            if (cont.isActive) cont.resumeWithException(error)
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {

                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                            // Inyectar JS ANTES de que los scripts de la pagina se ejecuten
                            // Esto permite interceptar XHR/fetch antes de que la pagina los use
                            if (preloadJs != null && !finished) {
                                view?.evaluateJavascript(preloadJs) { _ ->
                                    LogCollector.log("WEBVIEW", "Preload JS inyectado en onPageStarted")
                                }
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val redirectUrl = request?.url?.toString() ?: return false
                            // Ignorar recursos internos
                            if (redirectUrl.contains("beacon.min.js") ||
                                redirectUrl.contains("doubleclick.net") ||
                                redirectUrl.contains("googlesyndication")) {
                                return false
                            }
                            if (redirectCapture && redirectUrl != url) {
                                LogCollector.log("WEBVIEW", "Redirect capturado: $redirectUrl")
                                finish(redirectUrl)
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            handler.postDelayed({
                                if (finished) return@postDelayed
                                view?.evaluateJavascript(js) { result ->
                                    if (finished) return@evaluateJavascript
                                    // evaluateJavascript returns JSON-serialized results.
                                    // If JS returns a string, it comes wrapped in quotes: "DIRECT:https://..."
                                    // Strip surrounding quotes to get the actual string value.
                                    var cleaned = result?.trim() ?: ""
                                    if (cleaned.length >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                                        cleaned = cleaned.substring(1, cleaned.length - 1)
                                    }
                                    if (redirectCapture) {
                                        if (cleaned.isNotEmpty() && cleaned != "null") {
                                            finish(cleaned)
                                        } else {
                                            // Esperar un poco mas y reintentar
                                            handler.postDelayed({
                                                if (finished) return@postDelayed
                                                view?.evaluateJavascript(js) { result2 ->
                                                    var r2 = result2?.trim() ?: ""
                                                    if (r2.length >= 2 && r2.startsWith("\"") && r2.endsWith("\"")) {
                                                        r2 = r2.substring(1, r2.length - 1)
                                                    }
                                                    finish(r2)
                                                }
                                            }, extraJsWaitMs)
                                        }
                                    } else {
                                        finish(cleaned)
                                    }
                                }
                            }, waitForMs)
                        }
                    }

                    LogCollector.log("WEBVIEW", "Cargando: $url")
                    webView.loadUrl(url)

                    // Timeout de seguridad
                    handler.postDelayed({
                        if (!finished) {
                            finished = true
                            try { webView.destroy() } catch (_: Exception) {}
                            if (cont.isActive) cont.resumeWithException(
                                Exception("WebView timeout para $url")
                            )
                        }
                    }, 30000)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
    }
}
