package com.blackhole.browser

import android.content.Context
import android.util.Base64
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogDataToolkitBinding
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Encode/decode/hash utilities for Advance mode. Everything here runs
 * locally against the pasted text - no network call, nothing sent anywhere.
 */
object DataToolkit {

    private enum class Operation(val label: String) {
        BASE64_ENCODE("Base64 encode"),
        BASE64_DECODE("Base64 decode"),
        URL_ENCODE("URL encode"),
        URL_DECODE("URL decode"),
        HEX_ENCODE("Hex encode"),
        HEX_DECODE("Hex decode"),
        MD5("Hash: MD5"),
        SHA1("Hash: SHA-1"),
        SHA256("Hash: SHA-256"),
        SHA512("Hash: SHA-512")
    }

    fun show(context: Context) {
        val binding = DialogDataToolkitBinding.inflate(LayoutInflater.from(context))

        binding.toolkitOperation.adapter = ArrayAdapter(
            context,
            R.layout.spinner_item_dark,
            Operation.values().map { it.label }
        ).apply { setDropDownViewResource(R.layout.spinner_dropdown_item_dark) }

        binding.btnRunToolkit.setOnClickListener {
            val input = binding.toolkitInput.text.toString()
            val selectedLabel = binding.toolkitOperation.selectedItem as? String
            val operation = Operation.values().find { it.label == selectedLabel }
            binding.toolkitOutput.text = if (operation == null) {
                "Pick an operation."
            } else {
                runOperation(operation, input)
            }
        }

        AlertDialog.Builder(context)
            .setTitle("Data Toolkit")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun runOperation(operation: Operation, input: String): String {
        return try {
            when (operation) {
                Operation.BASE64_ENCODE -> Base64.encodeToString(input.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                Operation.BASE64_DECODE -> String(Base64.decode(input.trim(), Base64.DEFAULT), Charsets.UTF_8)
                Operation.URL_ENCODE -> URLEncoder.encode(input, "UTF-8")
                Operation.URL_DECODE -> URLDecoder.decode(input, "UTF-8")
                Operation.HEX_ENCODE -> input.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
                Operation.HEX_DECODE -> hexToString(input.trim())
                Operation.MD5 -> hash(input, "MD5")
                Operation.SHA1 -> hash(input, "SHA-1")
                Operation.SHA256 -> hash(input, "SHA-256")
                Operation.SHA512 -> hash(input, "SHA-512")
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun hash(input: String, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm).digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hexToString(hex: String): String {
        val clean = hex.replace(Regex("\\s"), "")
        require(clean.length % 2 == 0) { "Hex string must have an even number of characters" }
        val bytes = ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }
}
