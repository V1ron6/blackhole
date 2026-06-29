package com.blackhole.browser

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Lightweight host-based ad/tracker blocker.
 *
 * Loads a hosts-file style blocklist from assets (one domain per line,
 * '#' comments allowed) into a HashSet for O(1) lookup. Any WebView
 * request whose host matches (exactly or as a subdomain) is denied
 * with an empty 200 response instead of being passed to the network -
 * this avoids broken-image/error flashes a 404 would cause.
 */
class AdBlocker(context: Context) {

    private val blockedHosts: HashSet<String> = HashSet()

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
     * Returns true if [host] or any parent domain of it is in the blocklist.
     * e.g. blocking "doubleclick.net" also blocks "ads.doubleclick.net".
     */
    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var candidate = host.lowercase()
        while (true) {
            if (blockedHosts.contains(candidate)) return true
            val dotIndex = candidate.indexOf('.')
            if (dotIndex == -1) return false
            candidate = candidate.substring(dotIndex + 1)
        }
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
    }
}
