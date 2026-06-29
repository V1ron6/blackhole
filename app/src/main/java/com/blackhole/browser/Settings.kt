package com.blackhole.browser

import android.content.Context
import android.content.SharedPreferences

enum class SecurityMode { STRICT, CTF }

/**
 * Thin SharedPreferences wrapper for user-controlled security settings.
 *
 * STRICT  - HTTPS only, http:// navigation/sub-resources blocked outright.
 * CTF     - http:// allowed (most CTF/wargame boxes serve plain HTTP on
 *           internal ranges), TLS cert errors still fail closed either way -
 *           that protection is never disabled, even in CTF mode.
 *
 * Proxy settings route all WebView traffic through a SOCKS proxy (e.g. Orbot/Tor
 * on 127.0.0.1:9050, or any SOCKS5 proxy) via androidx.webkit's ProxyController.
 * This does not "randomize" your IP per-request - it routes through a fixed
 * exit point of whatever proxy/Tor circuit you've configured. Be upfront with
 * yourself about that distinction.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("blackhole_settings", Context.MODE_PRIVATE)

    var securityMode: SecurityMode
        get() = if (prefs.getString(KEY_SECURITY_MODE, MODE_STRICT) == MODE_CTF)
            SecurityMode.CTF else SecurityMode.STRICT
        set(value) {
            prefs.edit().putString(
                KEY_SECURITY_MODE,
                if (value == SecurityMode.CTF) MODE_CTF else MODE_STRICT
            ).apply()
        }

    var proxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PROXY_ENABLED, value).apply()

    var proxyHost: String
        get() = prefs.getString(KEY_PROXY_HOST, DEFAULT_PROXY_HOST) ?: DEFAULT_PROXY_HOST
        set(value) = prefs.edit().putString(KEY_PROXY_HOST, value).apply()

    var proxyPort: String
        get() = prefs.getString(KEY_PROXY_PORT, DEFAULT_PROXY_PORT) ?: DEFAULT_PROXY_PORT
        set(value) = prefs.edit().putString(KEY_PROXY_PORT, value).apply()

    /** SOCKS5 is the right scheme for Orbot/Tor; supports plain SOCKS proxies too. */
    var proxyScheme: String
        get() = prefs.getString(KEY_PROXY_SCHEME, DEFAULT_PROXY_SCHEME) ?: DEFAULT_PROXY_SCHEME
        set(value) = prefs.edit().putString(KEY_PROXY_SCHEME, value).apply()

    companion object {
        private const val KEY_SECURITY_MODE = "security_mode"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val KEY_PROXY_SCHEME = "proxy_scheme"

        private const val MODE_STRICT = "strict"
        private const val MODE_CTF = "ctf"

        // Orbot's default local SOCKS5 port.
        const val DEFAULT_PROXY_HOST = "127.0.0.1"
        const val DEFAULT_PROXY_PORT = "9050"
        const val DEFAULT_PROXY_SCHEME = "socks5"
    }
}
