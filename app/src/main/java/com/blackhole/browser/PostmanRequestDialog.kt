package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogPostmanBinding
import java.net.HttpURLConnection
import java.net.URL

/**
 * Postman-like request builder/tester, available from Intermediate mode
 * upward. Deliberately separate from the tab flow: it opens its own dialog,
 * makes its own connection, and never touches the active WebView or its
 * cookies/session state.
 */
object PostmanRequestDialog {

    private const val MAX_RESPONSE_CHARS = 20_000

    fun show(context: Context) {
        val binding = DialogPostmanBinding.inflate(LayoutInflater.from(context))

        binding.methodSpinner.adapter = ArrayAdapter(
            context,
            R.layout.spinner_item_dark,
            HttpMethod.values().map { it.displayName }
        ).apply { setDropDownViewResource(R.layout.spinner_dropdown_item_dark) }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Postman Request")
            .setView(binding.root)
            .setPositiveButton("Send", null)
            .setNegativeButton("Close", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                sendRequest(context, binding)
            }
        }
        dialog.show()
    }

    private fun sendRequest(context: Context, binding: DialogPostmanBinding) {
        val urlText = binding.inputUrl.text.toString().trim()
        if (urlText.isBlank()) {
            binding.responseOutput.text = "Enter a URL first."
            return
        }

        val selectedLabel = binding.methodSpinner.selectedItem as? String
        val method = HttpMethod.values().find { it.displayName == selectedLabel } ?: HttpMethod.GET
        val headersText = binding.inputHeaders.text.toString()
        val bodyText = binding.inputBody.text.toString()

        binding.responseOutput.text = "Sending\u2026"
        val mainHandler = Handler(context.mainLooper)

        Thread {
            val result = try {
                val connection = URL(urlText).openConnection() as HttpURLConnection
                connection.requestMethod = method.name
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000

                headersText.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.contains(":") }
                    .forEach { line ->
                        val parts = line.split(":", limit = 2)
                        connection.setRequestProperty(parts[0].trim(), parts.getOrElse(1) { "" }.trim())
                    }

                val methodTakesBody = method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH
                if (methodTakesBody && bodyText.isNotBlank()) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(bodyText.toByteArray(Charsets.UTF_8)) }
                }

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val headerDump = connection.headerFields.entries
                    .filter { it.key != null }
                    .joinToString("\n") { "${it.key}: ${it.value.joinToString(", ")}" }
                connection.disconnect()

                "Status: $code\n\n$headerDump\n\n$responseBody"
            } catch (e: Exception) {
                "Request failed: ${e.message}"
            }

            mainHandler.post {
                binding.responseOutput.text = result.take(MAX_RESPONSE_CHARS)
            }
        }.start()
    }
}
