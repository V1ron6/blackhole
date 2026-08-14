package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogDnsLookupBinding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * DNS record lookup for the active tab's host, via Google's DNS-over-HTTPS
 * JSON API (dns.google) rather than a hand-rolled binary DNS client over
 * raw UDP - simpler, more reliable, and reuses the same HttpURLConnection +
 * JSON pattern used everywhere else in this app. Trade-off: results come
 * from Google's resolver, not necessarily what the device's own DNS would
 * return (relevant for internal/split-horizon domains).
 */
object DnsLookup {

    private val recordTypes = listOf("A", "AAAA", "CNAME", "MX", "NS", "TXT")

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

        val binding = DialogDnsLookupBinding.inflate(LayoutInflater.from(context))
        binding.dnsTargetHost.text = host
        val mainHandler = Handler(context.mainLooper)

        AlertDialog.Builder(context)
            .setTitle("DNS Lookup")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()

        Thread {
            val results = StringBuilder()
            var anyFound = false
            for (type in recordTypes) {
                try {
                    val records = queryType(host, type)
                    if (records.isNotEmpty()) {
                        anyFound = true
                        results.append("$type\n")
                        records.forEach { results.append("  $it\n") }
                        results.append("\n")
                    }
                } catch (e: Exception) {
                    results.append("$type: query failed (${e.message})\n\n")
                }
            }
            if (!anyFound) results.append("No records found for any queried type.")

            mainHandler.post {
                binding.dnsStatus.text = "Done"
                binding.dnsOutput.text = results.toString().trim()
            }
        }.start()
    }

    private fun queryType(host: String, type: String): List<String> {
        val url = URL("https://dns.google/resolve?name=${java.net.URLEncoder.encode(host, "UTF-8")}&type=$type")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/dns-json")
        connection.connect()

        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            return emptyList()
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = JSONObject(body)
        val answers = json.optJSONArray("Answer") ?: return emptyList()
        return (0 until answers.length()).map { i ->
            answers.getJSONObject(i).optString("data")
        }
    }
}
