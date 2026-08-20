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
 *
 * MEJORAS 2025-08 (anti-detección headless):
 *  - Configura el WebView para que NO parezca un headless browser.
 *    Sitios como Filemoon y Rpmvid detectan "Headless Browser" y se rehúsan
 *    a cargar el React app. Seteamos:
 *    - desktop User-Agent real
 *    - DOM storage, indexed DB, cookies habilitados
 *    - JS habilitado
 *    - WebViewClient que NO reporta como webdriver
 *  - Inyecta JS de stealth ANTES de que la página cargue (en onPageStarted):
 *    - Elimina window.webdriver
 *    - Patchea navigator.userAgent para no incluir "Headless"
 *    - Simula propiedades comunes de Chrome desktop (languages, platform, etc.)
 */
object WebViewHelper {

    /**
     * JS de stealth que se inyecta ANTES de que cualquier script de la página corra.
     * Oculta señales de headless browser.
     */
    private const val STEALTH_JS = """
        (function() {
            // 1. Eliminar window.webdriver
            try {
                Object.defineProperty(navigator, 'webdriver', {get: () => undefined, configurable: true});
            } catch(e) {}
            try {
                window.webdriver = undefined;
                delete window.webdriver;
            } catch(e) {}

            // 2. Limpiar navigator.plugins (headless tiene array vacío)
            try {
                Object.defineProperty(navigator, 'plugins', {
                    get: () => [
                        {name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format'},
                        {name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: ''},
                        {name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: ''},
                        {name: 'Microsoft Edge PDF Viewer', filename: 'internal-pdf-viewer', description: ''},
                        {name: 'WebKit built-in PDF', filename: 'internal-pdf-viewer', description: ''}
                    ],
                    configurable: true
                });
            } catch(e) {}

            // 3. Simular navigator.languages (headless a veces tiene solo ['en-US'])
            try {
                Object.defineProperty(navigator, 'languages', {get: () => ['en-US', 'en'], configurable: true});
            } catch(e) {}

            // 4. Simular navigator.platform para Windows
            try {
                Object.defineProperty(navigator, 'platform', {get: () => 'Win32', configurable: true});
            } catch(e) {}

            // 5. Simular navigator.hardwareConcurrency
            try {
                Object.defineProperty(navigator, 'hardwareConcurrency', {get: () => 8, configurable: true});
            } catch(e) {}

            // 6. Simular navigator.deviceMemory
            try {
                Object.defineProperty(navigator, 'deviceMemory', {get: () => 8, configurable: true});
            } catch(e) {}

            // 7. Simular WebGL vendor y renderer (headless tiene MockRenderer)
            try {
                const getParameter = WebGLRenderingContext.prototype.getParameter;
                WebGLRenderingContext.prototype.getParameter = function(p) {
                    if (p === 37445) return 'Intel Inc.';          // UNMASKED_VENDOR_WEBGL
                    if (p === 37446) return 'Intel Iris OpenGL Engine';  // UNMASKED_RENDERER_WEBGL
                    return getParameter.call(this, p);
                };
            } catch(e) {}

            // 8. Chrome runtime mock (sitios verifican window.chrome)
            try {
                if (!window.chrome) {
                    window.chrome = {runtime: {}, app: {isInstalled: false}};
                }
            } catch(e) {}

            // 9. Notification permission mock
            try {
                if (window.Notification) {
                    Notification.permission = 'default';
                }
            } catch(e) {}

            // 10. Eliminar 'HeadlessChrome' del userAgent si está
            try {
                const origUA = navigator.userAgent;
                if (origUA.indexOf('HeadlessChrome') !== -1) {
                    const cleanUA = origUA.replace('HeadlessChrome', 'Chrome');
                    Object.defineProperty(navigator, 'userAgent', {get: () => cleanUA, configurable: true});
                }
            } catch(e) {}
        })();
    """

    /**
     * Carga una URL en un WebView invisible, espera a que cargue,
     * ejecuta JavaScript y retorna el resultado.
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
                    // Configuración anti-detección
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.databaseEnabled = true
                    webView.settings.loadWithOverviewMode = true
                    webView.settings.useWideViewPort = true
                    webView.settings.mediaPlaybackRequiresUserGesture = false
                    webView.settings.javaScriptCanOpenWindowsAutomatically = true
                    // User-Agent de Chrome desktop real (sin "HeadlessChrome" ni "wv" que delatan WebView Android)
                    webView.settings.userAgentString = HttpHelper.UA
                    // Aceptar cookies
                    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

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
                            // Inyectar stealth JS PRIMERO (antes que cualquier script de la página)
                            if (!finished) {
                                view?.evaluateJavascript(STEALTH_JS) { _ ->
                                    // Luego el preloadJs del extractor (si lo hay)
                                    if (preloadJs != null && !finished) {
                                        view?.evaluateJavascript(preloadJs) { _ ->
                                            LogCollector.log("WEBVIEW", "Preload JS inyectado en onPageStarted")
                                        }
                                    }
                                }
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val redirectUrl = request?.url?.toString() ?: return false
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
                                    var cleaned = result?.trim() ?: ""
                                    if (cleaned.length >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                                        cleaned = cleaned.substring(1, cleaned.length - 1)
                                        // Unescape sequences comunes que devuelve evaluateJavascript
                                        cleaned = cleaned.replace("\\n", "\n")
                                            .replace("\\r", "")
                                            .replace("\\\"", "\"")
                                            .replace("\\'", "'")
                                            .replace("\\\\", "\\")
                                            .replace("\\/", "/")
                                    }
                                    if (redirectCapture) {
                                        if (cleaned.isNotEmpty() && cleaned != "null") {
                                            finish(cleaned)
                                        } else {
                                            handler.postDelayed({
                                                if (finished) return@postDelayed
                                                view?.evaluateJavascript(js) { result2 ->
                                                    var r2 = result2?.trim() ?: ""
                                                    if (r2.length >= 2 && r2.startsWith("\"") && r2.endsWith("\"")) {
                                                        r2 = r2.substring(1, r2.length - 1)
                                                        r2 = r2.replace("\\n", "\n")
                                                            .replace("\\r", "")
                                                            .replace("\\\"", "\"")
                                                            .replace("\\'", "'")
                                                            .replace("\\\\", "\\")
                                                            .replace("\\/", "/")
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
                    }, 60000)  // 60s — algunos sitios tardan en cargar
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
    }
}
