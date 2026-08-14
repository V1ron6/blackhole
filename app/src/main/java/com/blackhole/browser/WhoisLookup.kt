package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogWhoisBinding
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * WHOIS lookup for the active tab's host, via the standard two-step
 * process: query whois.iana.org first to find which registry actually
 * holds the TLD's records, then query that server directly. Raw TCP to
 * port 43, plain text protocol - no HTTP API involved.
 */
object WhoisLookup {

    private const val MAX_CHARS_SHOWN = 12_000
    private const val SOCKET_TIMEOUT_MS = 10_000

    fun show(context: Context, webView: WebView?) {
        val pageUrl = webView?.url
        val host = try {
            pageUrl?.let { URL(it).host }
        } catch (e: Exception) {
            null
        }
        if (host.isNullOrBlank()) {
            Toast.makeText(context, "No page loaded to determine the host", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogWhoisBinding.inflate(LayoutInflater.from(context))
        binding.whoisTargetHost.text = host
        val mainHandler = Handler(context.mainLooper)

        AlertDialog.Builder(context)
            .setTitle("WHOIS")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()

        Thread {
            try {
                val ianaResponse = queryWhois("whois.iana.org", host)
                val referMatch = Regex("(?im)^refer:\\s*(\\S+)").find(ianaResponse)
                val finalResponse = if (referMatch != null) {
                    val referServer = referMatch.groupValues[1]
                    try {
                        queryWhois(referServer, host)
                    } catch (e: Exception) {
                        "$ianaResponse\n\n(Could not query referred server $referServer: ${e.message})"
                    }
                } else {
                    ianaResponse
                }

                mainHandler.post {
                    binding.whoisStatus.text = "Done"
                    binding.whoisOutput.text = finalResponse.take(MAX_CHARS_SHOWN) +
                        if (finalResponse.length > MAX_CHARS_SHOWN) "\n\n\u2026 (truncated)" else ""
                }
            } catch (e: Exception) {
                mainHandler.post {
                    binding.whoisStatus.text = "Lookup failed: ${e.message}"
                }
            }
        }.start()
    }

    private fun queryWhois(server: String, query: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server, 43), SOCKET_TIMEOUT_MS)
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.getOutputStream().apply {
                write("$query\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            return socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
        }
    }
}
