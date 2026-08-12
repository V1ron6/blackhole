package com.blackhole.browser

import android.content.Context
import android.view.LayoutInflater
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogCookieInspectorBinding

/**
 * Lists cookies CookieManager currently has for the active tab's page.
 *
 * Important context: Blackhole disables cookies globally
 * (CookieManager.setAcceptCookie(false) in SecureWebView) as one of its core
 * privacy features. That means this will almost always report zero cookies
 * - which is expected, not a bug. Its actual use is verifying that claim
 * holds for a given page (e.g. while testing whether a site tries to set
 * cookies at all), not inspecting a live session's cookie jar. If you want
 * a tool that shows a site's real cookie behavior, cookies would need a
 * per-tab opt-in toggle first (like the existing JS toggle) - that's not
 * built yet.
 */
object CookieInspector {

    fun show(context: Context, webView: WebView?) {
        val url = webView?.url
        if (url.isNullOrBlank()) {
            Toast.makeText(context, "No page loaded to inspect", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogCookieInspectorBinding.inflate(LayoutInflater.from(context))
        binding.cookieTargetUrl.text = url

        val rawCookies = CookieManager.getInstance().getCookie(url)
        val entries = rawCookies
            ?.split(";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        if (entries.isEmpty()) {
            val empty = TextView(context).apply {
                textSize = 12f
                text = "No cookies found for this page. Blackhole blocks all cookies by " +
                    "default (see the class doc comment / README for why), so this is the " +
                    "expected result unless that's changed."
                setTextColor(context.getColor(R.color.bh_text_dim))
            }
            binding.cookieResults.addView(empty)
        } else {
            val density = context.resources.displayMetrics.density
            val verticalPaddingPx = (8 * density).toInt()
            entries.forEach { cookie ->
                val row = TextView(context).apply {
                    textSize = 12f
                    setPadding(0, verticalPaddingPx, 0, verticalPaddingPx)
                    text = cookie
                    setTextColor(context.getColor(R.color.bh_text))
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                binding.cookieResults.addView(row)
            }
        }

        AlertDialog.Builder(context)
            .setTitle("Cookies (${entries.size})")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }
}
