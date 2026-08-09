package com.blackhole.browser

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.webkit.WebView
import com.blackhole.browser.databinding.DialogConsoleBinding

/**
 * JavaScript console for Advance mode and above. Runs commands via
 * WebView.evaluateJavascript against the currently active tab - the same
 * page-context sandbox any content script runs in, nothing more privileged.
 * History is in-memory only for the life of the dialog; nothing is persisted.
 */
object JavaScriptConsole {

    fun show(context: Context, webView: WebView?) {
        if (webView == null) {
            Toast.makeText(context, "No active tab to run JavaScript in", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogConsoleBinding.inflate(LayoutInflater.from(context))
        val history = StringBuilder()

        fun appendHistory(line: String) {
            if (history.isNotEmpty()) history.append("\n")
            history.append(line)
            binding.consoleOutput.text = history.toString()
            binding.consoleScroll.post {
                binding.consoleScroll.fullScroll(View.FOCUS_DOWN)
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("JavaScript Console")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .create()

        val runCommand = {
            val js = binding.consoleInput.text.toString()
            if (js.isNotBlank()) {
                appendHistory("> $js")
                webView.evaluateJavascript(js) { result ->
                    appendHistory(result ?: "undefined")
                }
                binding.consoleInput.setText("")
            }
        }

        binding.btnRun.setOnClickListener { runCommand() }

        dialog.show()
    }
}
