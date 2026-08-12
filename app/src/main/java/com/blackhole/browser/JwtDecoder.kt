package com.blackhole.browser

import android.content.Context
import android.util.Base64
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogJwtDecoderBinding
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Decodes a JWT's header and payload and flags expiry. This is decoding
 * only, not verification - there's no key entry here, so it never confirms
 * the signature is valid. Treat the decoded claims as "what this token
 * claims about itself," not "what's been cryptographically proven."
 */
object JwtDecoder {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)

    fun show(context: Context) {
        val binding = DialogJwtDecoderBinding.inflate(LayoutInflater.from(context))

        binding.btnDecodeJwt.setOnClickListener {
            decode(context, binding)
        }

        AlertDialog.Builder(context)
            .setTitle("JWT Decoder")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun decode(context: Context, binding: DialogJwtDecoderBinding) {
        val token = binding.jwtInput.text.toString().trim()
        val parts = token.split(".")
        if (parts.size != 3) {
            binding.jwtExpiryStatus.text = "Not a valid JWT - expected 3 dot-separated parts, found ${parts.size}."
            binding.jwtExpiryStatus.setTextColor(context.getColor(R.color.bh_danger))
            binding.jwtHeaderOutput.text = ""
            binding.jwtPayloadOutput.text = ""
            binding.jwtSignatureOutput.text = ""
            return
        }

        try {
            val headerJson = JSONObject(base64UrlDecode(parts[0]))
            val payloadJson = JSONObject(base64UrlDecode(parts[1]))

            binding.jwtHeaderOutput.text = headerJson.toString(2)
            binding.jwtPayloadOutput.text = payloadJson.toString(2)
            binding.jwtSignatureOutput.text = parts[2]

            renderExpiry(context, binding, payloadJson)
        } catch (e: Exception) {
            binding.jwtExpiryStatus.text = "Could not decode: ${e.message}"
            binding.jwtExpiryStatus.setTextColor(context.getColor(R.color.bh_danger))
            binding.jwtHeaderOutput.text = ""
            binding.jwtPayloadOutput.text = ""
            binding.jwtSignatureOutput.text = ""
        }
    }

    private fun renderExpiry(context: Context, binding: DialogJwtDecoderBinding, payload: JSONObject) {
        if (!payload.has("exp")) {
            binding.jwtExpiryStatus.text = "No \"exp\" claim - token has no expiry."
            binding.jwtExpiryStatus.setTextColor(context.getColor(R.color.bh_text_dim))
            return
        }

        val expSeconds = payload.optLong("exp", -1L)
        if (expSeconds < 0) {
            binding.jwtExpiryStatus.text = "\"exp\" claim is present but not a valid number."
            binding.jwtExpiryStatus.setTextColor(context.getColor(R.color.bh_warning))
            return
        }

        val expDate = Date(expSeconds * 1000)
        val now = Date()
        if (expDate.before(now)) {
            binding.jwtExpiryStatus.text = "\u2717 EXPIRED at ${dateFormat.format(expDate)}"
            binding.jwtExpiryStatus.setTextColor(context.getColor(R.color.bh_danger))
        } else {
            binding.jwtExpiryStatus.text = "\u2713 Valid until ${dateFormat.format(expDate)}"
            binding.jwtExpiryStatus.setTextColor(context.getColor(R.color.bh_success))
        }
    }

    /**
     * Decode a base64url segment (no padding, - and _ instead of + and /)
     * into a UTF-8 string.
     */
    private fun base64UrlDecode(segment: String): String {
        var normalized = segment.replace('-', '+').replace('_', '/')
        val paddingNeeded = (4 - normalized.length % 4) % 4
        normalized += "=".repeat(paddingNeeded)
        val bytes = Base64.decode(normalized, Base64.DEFAULT)
        return String(bytes, Charsets.UTF_8)
    }
}
