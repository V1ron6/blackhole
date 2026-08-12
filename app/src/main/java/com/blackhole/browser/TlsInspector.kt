package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogTlsInspectorBinding
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Shows the TLS certificate chain for the active tab's current page: subject,
 * issuer, validity window, and Subject Alternative Names.
 *
 * Like the security header scanner, this opens its own HTTPS connection
 * rather than reading the WebView's actual handshake - Android's WebView
 * doesn't expose the full chain or SANs through its public API
 * (WebView.getCertificate() only gives issuedBy/issuedTo/validity, no SAN
 * list). This connection is separate from what the page you're looking at
 * actually negotiated, so a MITM proxy or CDN edge difference between
 * requests could show something slightly different from what loaded.
 */
object TlsInspector {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun show(context: Context, webView: WebView?) {
        val url = webView?.url
        if (url.isNullOrBlank()) {
            Toast.makeText(context, "No page loaded to inspect", Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("https://")) {
            Toast.makeText(context, "Not an HTTPS page - no certificate to inspect", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogTlsInspectorBinding.inflate(LayoutInflater.from(context))
        binding.tlsTargetUrl.text = url

        AlertDialog.Builder(context)
            .setTitle("TLS Certificate")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()

        val mainHandler = Handler(context.mainLooper)
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpsURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.connect()

                val chain = connection.serverCertificates
                    .filterIsInstance<X509Certificate>()
                connection.disconnect()

                mainHandler.post {
                    if (chain.isEmpty()) {
                        binding.tlsStatus.text = "No certificate chain returned."
                    } else {
                        binding.tlsStatus.text = "${chain.size} certificate(s) in chain"
                        renderChain(context, binding, chain)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    binding.tlsStatus.text = "Connection failed: ${e.message}"
                }
            }
        }.start()
    }

    private fun renderChain(context: Context, binding: DialogTlsInspectorBinding, chain: List<X509Certificate>) {
        binding.tlsResults.removeAllViews()
        val density = context.resources.displayMetrics.density
        val paddingPx = (10 * density).toInt()
        val now = Date()

        chain.forEachIndexed { index, cert ->
            val label = if (index == 0) "Leaf certificate" else "Chain certificate #${index + 1}"
            val expired = cert.notAfter.before(now)
            val expiringSoon = !expired && cert.notAfter.time - now.time < TimeUnit.DAYS.toMillis(30)
            val sans = try {
                cert.subjectAlternativeNames
                    ?.mapNotNull { entry -> (entry.getOrNull(1))?.toString() }
                    ?.joinToString(", ")
                    ?: "(none)"
            } catch (e: Exception) {
                "(could not read SANs: ${e.message})"
            }

            val statusLine = when {
                expired -> "\u2717 EXPIRED"
                expiringSoon -> "\u26A0 EXPIRES SOON"
                else -> "\u2713 VALID"
            }
            val statusColor = when {
                expired -> R.color.bh_danger
                expiringSoon -> R.color.bh_warning
                else -> R.color.bh_success
            }

            val row = TextView(context).apply {
                textSize = 12f
                setPadding(0, paddingPx, 0, paddingPx)
                setTextColor(context.getColor(R.color.bh_text))
                text = buildString {
                    append(label).append("\n")
                    append("Subject: ${cert.subjectX500Principal.name}\n")
                    append("Issuer: ${cert.issuerX500Principal.name}\n")
                    append("Valid: ${dateFormat.format(cert.notBefore)} \u2192 ${dateFormat.format(cert.notAfter)}\n")
                    append("SANs: $sans")
                }
            }
            binding.tlsResults.addView(row)

            val statusRow = TextView(context).apply {
                textSize = 11f
                setPadding(0, 0, 0, paddingPx)
                setTextColor(context.getColor(statusColor))
                text = statusLine
            }
            binding.tlsResults.addView(statusRow)
        }
    }
}
