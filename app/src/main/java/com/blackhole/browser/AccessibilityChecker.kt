package com.blackhole.browser

import android.content.Context
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogAccessibilityCheckerBinding
import org.json.JSONArray
import org.json.JSONTokener

/**
 * Scans the active tab's DOM for common structural accessibility issues:
 * images missing alt text, form inputs without an associated label,
 * missing <html lang>, missing page <title>, and icon-only links/buttons
 * with no accessible name. This is a structural scan only - no color
 * contrast or visual checks, since those need rendered pixel data that
 * isn't available from the DOM.
 */
object AccessibilityChecker {

    fun show(context: Context, webView: WebView?) {
        if (webView == null) {
            Toast.makeText(context, "No active tab to scan", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogAccessibilityCheckerBinding.inflate(LayoutInflater.from(context))

        AlertDialog.Builder(context)
            .setTitle("Accessibility Scan")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()

        val js = """
            (function() {
                var issues = [];

                var imgs = document.querySelectorAll('img');
                for (var i = 0; i < imgs.length; i++) {
                    if (!imgs[i].hasAttribute('alt')) {
                        issues.push('Image missing alt attribute: ' + (imgs[i].src || '(no src)').slice(0, 80));
                    }
                }

                var inputs = document.querySelectorAll('input, textarea, select');
                for (var j = 0; j < inputs.length; j++) {
                    var el = inputs[j];
                    if (el.type === 'hidden') continue;
                    var hasLabel = false;
                    if (el.id) {
                        hasLabel = document.querySelector('label[for="' + el.id + '"]') !== null;
                    }
                    if (!hasLabel && !el.getAttribute('aria-label') && !el.getAttribute('aria-labelledby')) {
                        issues.push('Form field missing a label: ' + (el.name || el.id || el.type || 'unnamed'));
                    }
                }

                var links = document.querySelectorAll('a, button');
                for (var k = 0; k < links.length; k++) {
                    var l = links[k];
                    var text = (l.textContent || '').trim();
                    if (!text && !l.getAttribute('aria-label') && !l.getAttribute('title')) {
                        issues.push('Link/button with no accessible name: <' + l.tagName.toLowerCase() + '>');
                    }
                }

                if (!document.documentElement.getAttribute('lang')) {
                    issues.push('Missing lang attribute on <html>');
                }
                if (!document.title || !document.title.trim()) {
                    issues.push('Missing or empty <title>');
                }

                return JSON.stringify(issues);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { rawResult ->
            renderResult(context, binding, rawResult)
        }
    }

    private fun renderResult(context: Context, binding: DialogAccessibilityCheckerBinding, rawResult: String?) {
        try {
            val unwrapped = JSONTokener(rawResult ?: "[]").nextValue() as? String
                ?: throw IllegalStateException("Unexpected result shape")
            val issues = JSONArray(unwrapped)

            if (issues.length() == 0) {
                binding.a11yStatus.text = "No structural issues found."
                binding.a11yStatus.setTextColor(context.getColor(R.color.bh_success))
                return
            }

            binding.a11yStatus.text = "${issues.length()} issue(s) found"
            binding.a11yStatus.setTextColor(context.getColor(R.color.bh_warning))

            val density = context.resources.displayMetrics.density
            val paddingPx = (8 * density).toInt()
            for (i in 0 until issues.length()) {
                val row = TextView(context).apply {
                    textSize = 12f
                    setPadding(0, paddingPx, 0, paddingPx)
                    text = "\u2022 ${issues.getString(i)}"
                    setTextColor(context.getColor(R.color.bh_text))
                }
                binding.a11yResults.addView(row)
            }
        } catch (e: Exception) {
            binding.a11yStatus.text = "Scan failed: ${e.message}"
            binding.a11yStatus.setTextColor(context.getColor(R.color.bh_danger))
        }
    }
}
