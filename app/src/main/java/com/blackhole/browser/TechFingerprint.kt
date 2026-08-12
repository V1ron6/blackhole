package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogTechFingerprintBinding
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fingerprints the tech stack behind the active tab's page by matching a
 * small curated set of signatures against response headers and page HTML -
 * generator meta tags, common framework markers, server headers, CDN
 * headers. This is a hand-rolled subset, not a Wappalyzer-scale signature
 * database (that's thousands of entries); it catches the common cases.
 */
object TechFingerprint {

    private data class Signature(val label: String, val category: String, val matcher: (headers: Map<String, String>, html: String) -> Boolean)

    private val signatures = listOf(
        Signature("WordPress", "CMS") { _, html -> html.contains("wp-content") || html.contains("wp-includes") },
        Signature("Drupal", "CMS") { _, html -> html.contains("Drupal.settings") || html.contains("/sites/default/files") },
        Signature("Joomla", "CMS") { _, html -> html.contains("/media/jui/") || html.contains("Joomla!") },
        Signature("Shopify", "E-commerce") { _, html -> html.contains("cdn.shopify.com") || html.contains("Shopify.theme") },
        Signature("Magento", "E-commerce") { _, html -> html.contains("Mage.Cookies") || html.contains("/static/frontend/") },
        Signature("React", "Frontend framework") { _, html -> html.contains("data-reactroot") || html.contains("__REACT_DEVTOOLS") || Regex("react(-dom)?[.@]").containsMatchIn(html) },
        Signature("Vue.js", "Frontend framework") { _, html -> html.contains("data-v-") || html.contains("__VUE__") || html.contains("vue.js") },
        Signature("Angular", "Frontend framework") { _, html -> html.contains("ng-version") || html.contains("ng-app") },
        Signature("Next.js", "Frontend framework") { _, html -> html.contains("__NEXT_DATA__") || html.contains("/_next/static/") },
        Signature("jQuery", "JS library") { _, html -> Regex("jquery[.-][\\d.]*\\.js|jquery\\.min\\.js").containsMatchIn(html) },
        Signature("Bootstrap", "CSS framework") { _, html -> html.contains("bootstrap.min.css") || html.contains("bootstrap.bundle") },
        Signature("Tailwind CSS", "CSS framework") { headers, html -> html.contains("tailwindcss") || Regex("class=\"[^\"]*(flex|grid)-[a-z]").containsMatchIn(html) && headers.isEmpty().not() && html.contains("tailwind") },
        Signature("Google Analytics", "Analytics") { _, html -> html.contains("google-analytics.com") || html.contains("gtag(") },
        Signature("Google Tag Manager", "Analytics") { _, html -> html.contains("googletagmanager.com") },
        Signature("Cloudflare", "CDN/proxy") { headers, _ -> headers["server"]?.contains("cloudflare", ignoreCase = true) == true || headers.containsKey("cf-ray") },
        Signature("Fastly", "CDN/proxy") { headers, _ -> headers.containsKey("x-served-by") && headers["x-served-by"]?.contains("cache") == true || headers.containsKey("fastly-debug-digest") },
        Signature("Nginx", "Web server") { headers, _ -> headers["server"]?.contains("nginx", ignoreCase = true) == true },
        Signature("Apache", "Web server") { headers, _ -> headers["server"]?.contains("apache", ignoreCase = true) == true },
        Signature("PHP", "Language/runtime") { headers, _ -> headers.containsKey("x-powered-by") && headers["x-powered-by"]?.contains("php", ignoreCase = true) == true },
        Signature("Express", "Language/runtime") { headers, _ -> headers["x-powered-by"]?.contains("express", ignoreCase = true) == true },
        Signature("ASP.NET", "Language/runtime") { headers, _ -> headers.containsKey("x-aspnet-version") || headers["x-powered-by"]?.contains("asp.net", ignoreCase = true) == true }
    )

    fun show(context: Context, webView: WebView?) {
        val url = webView?.url
        if (url.isNullOrBlank()) {
            Toast.makeText(context, "No page loaded to scan", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogTechFingerprintBinding.inflate(LayoutInflater.from(context))
        binding.fingerprintTargetUrl.text = url

        AlertDialog.Builder(context)
            .setTitle("Tech Fingerprint")
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
                connection.connect()
                val code = connection.responseCode
                val headers = connection.headerFields.entries
                    .filter { it.key != null }
                    .associate { it.key.lowercase() to it.value.joinToString("; ") }
                val html = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else ""
                connection.disconnect()

                val generatorTag = Regex("<meta[^>]*name=[\"']generator[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)

                val matches = signatures.filter { it.matcher(headers, html) }

                mainHandler.post {
                    binding.fingerprintStatus.text = if (matches.isEmpty() && generatorTag == null) {
                        "No known signatures matched (HTTP $code)."
                    } else {
                        "${matches.size} match(es) (HTTP $code)"
                    }
                    renderResults(context, binding, matches, generatorTag)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    binding.fingerprintStatus.text = "Scan failed: ${e.message}"
                }
            }
        }.start()
    }

    private fun renderResults(context: Context, binding: DialogTechFingerprintBinding, matches: List<Signature>, generatorTag: String?) {
        binding.fingerprintResults.removeAllViews()
        val density = context.resources.displayMetrics.density
        val paddingPx = (8 * density).toInt()

        if (generatorTag != null) {
            binding.fingerprintResults.addView(TextView(context).apply {
                textSize = 12f
                setPadding(0, paddingPx, 0, paddingPx)
                text = "Generator meta tag: $generatorTag"
                setTextColor(context.getColor(R.color.bh_accent))
            })
        }

        matches.groupBy { it.category }.forEach { (category, sigs) ->
            binding.fingerprintResults.addView(TextView(context).apply {
                textSize = 12f
                setPadding(0, paddingPx, 0, 0)
                text = category
                setTextColor(context.getColor(R.color.bh_text_dim))
            })
            sigs.forEach { sig ->
                binding.fingerprintResults.addView(TextView(context).apply {
                    textSize = 13f
                    setPadding(0, 2, 0, paddingPx)
                    text = "\u2713 ${sig.label}"
                    setTextColor(context.getColor(R.color.bh_success))
                })
            }
        }
    }
}
