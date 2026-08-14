package com.blackhole.browser

import android.content.Context
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogUserAgentBinding

/**
 * Overrides the User-Agent string sent by the active tab's WebView. Applies
 * to this tab only - a fresh tab or a session clear resets to the WebView's
 * default UA, since we never persist an override to Settings.
 */
object UserAgentSwitcher {

    private data class Preset(val label: String, val userAgent: String?)

    private val presets = listOf(
        Preset("Default (device WebView)", null),
        Preset(
            "Desktop Chrome (Windows)",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        ),
        Preset(
            "Desktop Firefox (Windows)",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        ),
        Preset(
            "iPhone Safari",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
        ),
        Preset(
            "Googlebot",
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        ),
        Preset("Custom\u2026", "")
    )

    fun show(context: Context, webView: WebView?) {
        if (webView == null) {
            Toast.makeText(context, "No active tab", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogUserAgentBinding.inflate(LayoutInflater.from(context))
        binding.uaPresetSpinner.adapter = ArrayAdapter(
            context,
            R.layout.spinner_item_dark,
            presets.map { it.label }
        ).apply { setDropDownViewResource(R.layout.spinner_dropdown_item_dark) }
        binding.uaCurrentValue.text = webView.settings.userAgentString

        binding.btnApplyUa.setOnClickListener {
            val selectedLabel = binding.uaPresetSpinner.selectedItem as? String
            val preset = presets.find { it.label == selectedLabel }
            val newUa = when {
                preset == null -> null
                preset.label == "Custom\u2026" -> binding.uaCustomInput.text.toString().trim().ifBlank { null }
                else -> preset.userAgent
            }

            if (newUa == null && preset?.label == "Custom\u2026") {
                Toast.makeText(context, "Enter a custom User-Agent string first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // null userAgent means "reset to WebView default" - WebSettings
            // doesn't accept null directly, so read back the system default.
            val defaultUa = android.webkit.WebSettings.getDefaultUserAgent(context)
            webView.settings.userAgentString = newUa ?: defaultUa
            binding.uaCurrentValue.text = webView.settings.userAgentString
            webView.reload()
            Toast.makeText(context, "User-Agent applied, tab reloaded", Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(context)
            .setTitle("User-Agent")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }
}
