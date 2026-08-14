package com.blackhole.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * Builds a single hardened WebView tab.
 *
 * Security posture:
 *  - Cookies and localStorage/sessionStorage OFF by default (always-private) -
 *    can be turned on via Settings > Session Manager for cases like staying
 *    logged into a CTF target. When on, third-party cookies stay blocked
 *    regardless. Session Manager resets to off, and wipes whatever was
 *    stored, every time Clear Session runs - see MainActivity.clearEverythingAndReset.
 *  - No HTTP cache, no form data, no autofill, no saved passwords
 *  - No file system access from web content (file:// disabled, no file upload-adjacent APIs)
 *  - JavaScript OFF by default; caller can enable per-tab if the user explicitly trusts a site
 *  - HTTPS-only in Strict mode; http:// is permitted in CTF mode (toggle in
 *    Settings) since many CTF/wargame boxes only serve plain HTTP on lab
 *    ranges. Either way, this is a deliberate user choice, never a silent
 *    default.
 *  - SSL errors are NEVER silently bypassed, in either mode - the load is
 *    always cancelled on a cert error.
 *  - Ad/tracker requests are blocked at the network layer before they're ever sent
 *  - Mixed content (https page loading http resources) is blocked
 *  - Geolocation, camera/mic prompts are denied by default (no permission grants wired up)
 */
@SuppressLint("SetJavaScriptEnabled")
object SecureWebView {

    fun create(
        webView: WebView,
        adBlocker: AdBlocker,
        appSettings: Settings,
        jsEnabled: Boolean = false,
        onUrlChanged: (String) -> Unit,
        onLoadingChanged: (Boolean) -> Unit,
        onBlockedInsecure: () -> Unit,
        onRequestLogged: (host: String, url: String, method: String, blocked: Boolean) -> Unit = { _, _, _, _ -> }
    ) {
        val settings: WebSettings = webView.settings

        // --- Core hardening ---
        settings.javaScriptEnabled = jsEnabled
        settings.domStorageEnabled = appSettings.sessionPersistenceEnabled
        settings.databaseEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.saveFormData = false
        settings.setGeolocationEnabled(false)
        settings.mixedContentMode = if (appSettings.securityMode == SecurityMode.STRICT)
            WebSettings.MIXED_CONTENT_NEVER_ALLOW else WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.mediaPlaybackRequiresUserGesture = true

        // Cookies: off by default (always-private). Session Manager in
        // Settings can turn first-party cookies on for cases like staying
        // logged into a CTF target - third-party cookies stay blocked
        // either way, since there's no legitimate reason this app needs them.
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(appSettings.sessionPersistenceEnabled)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                if (uri.scheme == "blackhole") {
                    when (uri.host) {
                        "navigate" -> {
                            val query = uri.getQueryParameter("q").orEmpty().trim()
                            if (query.isNotBlank()) {
                                view.loadUrl(resolveInput(query, appSettings.securityMode))
                            }
                        }

                        "settings" -> {
                            view.context.startActivity(Intent(view.context, SettingsActivity::class.java))
                        }
                    }
                    return true
                }
                if (uri.scheme == "http" && appSettings.securityMode == SecurityMode.STRICT) {
                    onBlockedInsecure()
                    return true // block, do not navigate
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host
                val urlString = request.url.toString()
                val method = request.method
                if (adBlocker.isBlocked(host)) {
                    if (!host.isNullOrEmpty()) onRequestLogged(host, urlString, method, true)
                    return adBlocker.emptyResponse()
                }
                // Belt-and-suspenders: in Strict mode, block any sub-resource
                // fetched over plain HTTP too. In CTF mode, allow it - lab
                // targets commonly serve images/scripts over http as well.
                if (request.url.scheme == "http" && appSettings.securityMode == SecurityMode.STRICT) {
                    if (!host.isNullOrEmpty()) onRequestLogged(host, urlString, method, true)
                    return adBlocker.emptyResponse()
                }
                if (!host.isNullOrEmpty()) onRequestLogged(host, urlString, method, false)
                return null
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                // Never proceed on a cert error. Fail closed.
                handler.cancel()
                Toast.makeText(
                    view.context,
                    view.context.getString(R.string.cert_error),
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                onLoadingChanged(true)
                url?.let { if (!it.startsWith("file://")) onUrlChanged(it) }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                onLoadingChanged(false)
            }
        }

        webView.webChromeClient = android.webkit.WebChromeClient()
    }

    /** Resolves a search-bar query: treats it as a URL if it looks like one, else searches. */
    fun resolveInput(input: String, securityMode: SecurityMode): String {
        val trimmed = input.trim()
        val looksLikeUrl = trimmed.contains(".") && !trimmed.contains(" ")
        return when {
            trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("http://") ->
                if (securityMode == SecurityMode.CTF) trimmed
                else trimmed.replaceFirst("http://", "https://")
            // Bare IP:port or hostname with no scheme, in CTF mode, is almost
            // always a lab target - default it to http:// instead of https://
            // so it doesn't fail a TLS handshake against a plain HTTP service.
            looksLikeUrl && securityMode == SecurityMode.CTF -> "http://$trimmed"
            looksLikeUrl -> "https://$trimmed"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(trimmed)
        }
    }
}
