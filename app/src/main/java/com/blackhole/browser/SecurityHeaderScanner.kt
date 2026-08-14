package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogSecurityScannerBinding
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the active tab's current page for common security response headers
 * and shows a pass/fail badge per header. Available from Advance mode up.
 *
 * This makes its own HEAD/GET request rather than reading the WebView's
 * actual response - WebViewClient doesn't expose response headers for the
 * top-level navigation, only for shouldInterceptRequest sub-resources. That
 * means it's a fresh request to the same URL, not necessarily byte-for-byte
 * what the page you're looking at received (a second request could hit a
 * different edge node, A/B test, etc.) - close enough for a quick posture
 * check, but worth knowing if you need exact parity with what loaded.
 */
object SecurityHeaderScanner {

    private data class HeaderCheck(
        val headerName: String,
        val label: String,
        val whyItMatters: String
    )

    private val checks = listOf(
        HeaderCheck("content-security-policy", "Content-Security-Policy", "Restricts what scripts/resources a page can load - a strong defense against XSS."),
        HeaderCheck("strict-transport-security", "Strict-Transport-Security", "Forces browsers to only connect over HTTPS for this host."),
        HeaderCheck("x-frame-options", "X-Frame-Options", "Blocks the page from being embedded in an iframe - defends against clickjacking."),
        HeaderCheck("x-content-type-options", "X-Content-Type-Options", "Stops browsers from MIME-sniffing responses into an unintended content type."),
        HeaderCheck("referrer-policy", "Referrer-Policy", "Controls how much of the URL is leaked to other sites via the Referer header."),
        HeaderCheck("permissions-policy", "Permissions-Policy", "Restricts which browser features/APIs (camera, geolocation, etc.) the page can use.")
    )

    fun show(context: Context, webView: WebView?) {
        val url = webView?.url
        if (url.isNullOrBlank() || url.startsWith("file://") || url.startsWith("data:")) {
            Toast.makeText(context, "No page loaded to scan", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogSecurityScannerBinding.inflate(LayoutInflater.from(context))
        binding.scannerTargetUrl.text = url

        AlertDialog.Builder(context)
            .setTitle("Security Headers")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()

        val mainHandler = Handler(context.mainLooper)
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = true
                connection.connect()

                val code = connection.responseCode
                // Header map keys are case-sensitive as returned by the server;
                // normalize to lowercase for matching.
                val headers = connection.headerFields.entries
                    .filter { it.key != null }
                    .associate { it.key.lowercase() to it.value.joinToString("; ") }
                connection.disconnect()

                mainHandler.post {
                    binding.scannerStatus.text = "HTTP $code \u2022 ${headers.size} response headers"
                    renderResults(context, binding, headers)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    binding.scannerStatus.text = "Scan failed: ${e.message}"
                }
            }
        }.start()
    }

    private fun renderResults(context: Context, binding: DialogSecurityScannerBinding, headers: Map<String, String>) {
        binding.scannerResults.removeAllViews()
        val density = context.resources.displayMetrics.density
        val verticalPaddingPx = (10 * density).toInt()
        checks.forEach { check ->
            val present = headers.containsKey(check.headerName)
            val row = TextView(context).apply {
                textSize = 12f
                setPadding(0, verticalPaddingPx, 0, verticalPaddingPx)
                val badge = if (present) "\u2713 PRESENT" else "\u2717 MISSING"
                val color = if (present) R.color.bh_success else R.color.bh_danger
                text = "$badge  \u2014  ${check.label}\n${check.whyItMatters}" +
                    if (present) "\n${headers[check.headerName]}" else ""
                setTextColor(context.getColor(color))
            }
            binding.scannerResults.addView(row)
        }
    }
}
