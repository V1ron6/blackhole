package com.blackhole.browser

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Host-based ad/tracker blocker, plus a per-site manual block/unblock
 * override the user controls from the toolbar (see MainActivity.btnSiteBlock).
 *
 * Three layers, most specific host wins, checked from most to least specific
 * (e.g. for "ads.example.com": "ads.example.com" checked before "example.com"):
 *  1. userAllowedHosts  - user explicitly unblocked this host; wins over everything
 *  2. userBlockedHosts  - user explicitly blocked this host (e.g. a distracting site)
 *  3. blockedHosts      - the static list loaded from assets (StevenBlack/hosts,
 *                         ads+tracking+malware, ~72k domains)
 *
 * The static list loads from assets (one domain per line, '#' comments
 * allowed) into a HashSet for O(1) lookup. Any WebView request whose host
 * matches (exactly or as a subdomain) is denied with an empty 200 response
 * instead of being passed to the network - this avoids broken-image/error
 * flashes a 404 would cause.
 */
class AdBlocker(context: Context) {

    private val blockedHosts: HashSet<String> = HashSet()
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val userBlockedHosts: MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_USER_BLOCKED, emptySet()) ?: emptySet())
    private val userAllowedHosts: MutableSet<String> =
        HashSet(prefs.getStringSet(KEY_USER_ALLOWED, emptySet()) ?: emptySet())

    init {
        try {
            context.assets.open(BLOCKLIST_FILE).use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEach
                        blockedHosts.add(line.lowercase())
                    }
                }
            }
        } catch (_: Exception) {
            // If the list fails to load, fail open rather than crash the browser.
        }
    }

    /**
     * Returns true if [host], or the most specific ancestor domain of it that
     * appears in any list, resolves to blocked. A user allow at a given level
     * beats a block at that same level, e.g. explicitly unblocking
     * "cdn.example.com" overrides a block on parent domain "example.com" -
     * but a block on "ads.example.com" specifically still applies even if
     * "example.com" itself is allowed.
     */
    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var candidate = host.lowercase()
        while (true) {
            if (userAllowedHosts.contains(candidate)) return false
            if (userBlockedHosts.contains(candidate) || blockedHosts.contains(candidate)) return true
            val dotIndex = candidate.indexOf('.')
            if (dotIndex == -1) return false
            candidate = candidate.substring(dotIndex + 1)
        }
    }

    /**
     * Flips the block state for the exact [host] (not its parent domain - if
     * "www.example.com" is currently blocked only because "example.com" is in
     * the static list, this unblocks "www.example.com" specifically, leaving
     * the rest of the domain's subdomains alone). Returns the new effective
     * state (true = now blocked).
     */
    fun toggleHost(host: String): Boolean {
        val normalized = host.lowercase()
        val currentlyBlocked = isBlocked(normalized)
        if (currentlyBlocked) {
            userBlockedHosts.remove(normalized)
            userAllowedHosts.add(normalized)
        } else {
            userAllowedHosts.remove(normalized)
            userBlockedHosts.add(normalized)
        }
        persist()
        return !currentlyBlocked
    }

    private fun persist() {
        prefs.edit()
            .putStringSet(KEY_USER_BLOCKED, HashSet(userBlockedHosts))
            .putStringSet(KEY_USER_ALLOWED, HashSet(userAllowedHosts))
            .apply()
    }

    /** Empty 200 response used to silently swallow a blocked request. */
    fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    companion object {
        private const val BLOCKLIST_FILE = "adblock_hosts.txt"
        private const val PREFS_FILE = "blackhole_adblock_overrides"
        private const val KEY_USER_BLOCKED = "user_blocked_hosts"
        private const val KEY_USER_ALLOWED = "user_allowed_hosts"
    }
}
