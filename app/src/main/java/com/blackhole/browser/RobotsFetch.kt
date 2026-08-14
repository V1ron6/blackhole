package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogRobotsFetchBinding
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches robots.txt / sitemap.xml for the active tab's host - a quick
 * recon shortcut so you don't have to manually type the path into the URL
 * bar and lose your current page.
 */
object RobotsFetch {

    private const val MAX_CHARS_SHOWN = 15_000

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
        val scheme = try {
            URL(pageUrl).protocol
        } catch (e: Exception) {
            "https"
        }

        val binding = DialogRobotsFetchBinding.inflate(LayoutInflater.from(context))
        binding.robotsTargetHost.text = "$scheme://$host"
        val mainHandler = Handler(context.mainLooper)

        fun fetch(path: String) {
            binding.robotsStatus.text = "Fetching $path\u2026"
            binding.robotsOutput.text = ""
            Thread {
                val result = try {
                    val connection = URL("$scheme://$host$path").openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.instanceFollowRedirects = true
                    connection.connect()
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    connection.disconnect()
                    "HTTP $code\n\n${body.take(MAX_CHARS_SHOWN)}" +
                        if (body.length > MAX_CHARS_SHOWN) "\n\n\u2026 (truncated)" else ""
                } catch (e: Exception) {
                    "Fetch failed: ${e.message}"
                }
                mainHandler.post {
                    binding.robotsStatus.text = path
                    binding.robotsOutput.text = result
                }
            }.start()
        }

        binding.btnFetchRobots.setOnClickListener { fetch("/robots.txt") }
        binding.btnFetchSitemap.setOnClickListener { fetch("/sitemap.xml") }

        AlertDialog.Builder(context)
            .setTitle("robots.txt / sitemap.xml")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()

        fetch("/robots.txt")
    }
}
