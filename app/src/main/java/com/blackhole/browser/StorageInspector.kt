package com.blackhole.browser

import android.content.Context
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogStorageInspectorBinding
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Reads localStorage/sessionStorage for the active tab's page via
 * WebView.evaluateJavascript - same page-context sandbox the JS console
 * runs in, nothing more privileged. Like the JS console, this runs
 * regardless of the per-tab JS toggle (evaluateJavascript ignores that
 * setting - an Android WebView quirk, not something this app controls).
 */
object StorageInspector {

    fun show(context: Context, webView: WebView?) {
        if (webView == null) {
            Toast.makeText(context, "No active tab to inspect", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogStorageInspectorBinding.inflate(LayoutInflater.from(context))
        binding.storageTargetUrl.text = webView.url ?: ""
        binding.localStorageOutput.text = "Reading\u2026"
        binding.sessionStorageOutput.text = "Reading\u2026"

        AlertDialog.Builder(context)
            .setTitle("Storage Inspector")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()

        val js = """
            (function() {
                function dump(storage) {
                    var out = {};
                    try {
                        for (var i = 0; i < storage.length; i++) {
                            var k = storage.key(i);
                            out[k] = storage.getItem(k);
                        }
                    } catch (e) { out.__error = String(e); }
                    return out;
                }
                return JSON.stringify({ local: dump(localStorage), session: dump(sessionStorage) });
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { rawResult ->
            renderResult(context, binding, rawResult)
        }
    }

    private fun renderResult(context: Context, binding: DialogStorageInspectorBinding, rawResult: String?) {
        try {
            // evaluateJavascript's callback gives back a JSON-encoded representation
            // of the JS return value - since we returned a string, that's a quoted,
            // escaped JSON string literal. Unwrap it once to get the real JSON text.
            val unwrapped = JSONTokener(rawResult ?: "null").nextValue() as? String
                ?: throw IllegalStateException("Unexpected result shape")
            val root = JSONObject(unwrapped)

            binding.localStorageOutput.text = formatStorageMap(root.optJSONObject("local"))
            binding.sessionStorageOutput.text = formatStorageMap(root.optJSONObject("session"))
        } catch (e: Exception) {
            binding.localStorageOutput.text = "Could not read storage: ${e.message}"
            binding.sessionStorageOutput.text = ""
        }
    }

    private fun formatStorageMap(obj: JSONObject?): String {
        if (obj == null || obj.length() == 0) return "(empty)"
        val keys = obj.keys()
        val lines = mutableListOf<String>()
        while (keys.hasNext()) {
            val key = keys.next()
            lines.add("$key = ${obj.optString(key)}")
        }
        return lines.joinToString("\n")
    }
}
