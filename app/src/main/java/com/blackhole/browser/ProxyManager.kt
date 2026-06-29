package com.blackhole.browser

import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

/**
 * Applies a SOCKS proxy (Orbot/Tor or any SOCKS5 proxy) to ALL WebView traffic
 * in the process - WebView's proxy override is process-wide, not per-tab, so
 * this affects every open tab simultaneously.
 *
 * Honest limitation: this routes traffic through whatever fixed exit point
 * the proxy/Tor circuit currently has. It is not per-request IP rotation.
 * If you want a *new* exit IP, you restart Orbot's circuit (or your proxy)
 * - Blackhole can't fabricate that on its own.
 *
 * Requires the device to support WebViewFeature.PROXY_OVERRIDE (true on all
 * modern WebView builds on API 29+, which is our minSdk).
 */
object ProxyManager {

    private val immediateExecutor = Executor { it.run() }

    fun isSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /** Routes all WebView traffic through host:port using the given scheme (e.g. "socks5"). */
    fun enable(scheme: String, host: String, port: String, onDone: () -> Unit = {}) {
        if (!isSupported()) {
            onDone()
            return
        }
        val rule = "$scheme://$host:$port"
        val config = ProxyConfig.Builder()
            .addProxyRule(rule)
            // Localhost/loopback CTF/lab targets should still resolve directly
            // even with the proxy on, otherwise local range boxes break.
            .addBypassRule("127.0.0.1")
            .addBypassRule("localhost")
            .build()
        ProxyController.getInstance().setProxyOverride(config, immediateExecutor) { onDone() }
    }

    fun disable(onDone: () -> Unit = {}) {
        if (!isSupported()) {
            onDone()
            return
        }
        ProxyController.getInstance().clearProxyOverride(immediateExecutor) { onDone() }
    }

    /** Call after toggling, before/after loading pages, to keep things in sync with Settings. */
    fun applyFromSettings(settings: Settings, onDone: () -> Unit = {}) {
        if (settings.proxyEnabled) {
            enable(settings.proxyScheme, settings.proxyHost, settings.proxyPort, onDone)
        } else {
            disable(onDone)
        }
    }
}
