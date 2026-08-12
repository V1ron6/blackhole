package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogTotpBinding
import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP generator: standard HMAC-SHA1-based 30-second, 6-digit
 * codes, same algorithm Google Authenticator and most 2FA apps use. Runs
 * entirely locally - the secret never leaves the device.
 */
object TotpGenerator {

    private const val TIME_STEP_SECONDS = 30L
    private const val DIGITS = 6

    fun show(context: Context) {
        val binding = DialogTotpBinding.inflate(LayoutInflater.from(context))
        val mainHandler = Handler(context.mainLooper)
        var running = false
        var secretBytes: ByteArray? = null

        val tickRunnable = object : Runnable {
            override fun run() {
                val secret = secretBytes ?: return
                val nowSeconds = System.currentTimeMillis() / 1000
                val counter = nowSeconds / TIME_STEP_SECONDS
                val secondsIntoStep = nowSeconds % TIME_STEP_SECONDS
                val secondsRemaining = TIME_STEP_SECONDS - secondsIntoStep

                try {
                    binding.totpCode.text = hotp(secret, counter, DIGITS)
                    binding.totpCountdown.text = "refreshes in ${secondsRemaining}s"
                } catch (e: Exception) {
                    binding.totpCode.text = ""
                    binding.totpCountdown.text = "Error: ${e.message}"
                    running = false
                    return
                }

                if (running) mainHandler.postDelayed(this, 1000)
            }
        }

        binding.btnStartTotp.setOnClickListener {
            val raw = binding.totpSecret.text.toString()
            try {
                secretBytes = base32Decode(raw)
            } catch (e: Exception) {
                binding.totpCode.text = ""
                binding.totpCountdown.text = "Invalid base32 secret: ${e.message}"
                return@setOnClickListener
            }
            running = true
            mainHandler.removeCallbacks(tickRunnable)
            tickRunnable.run()
        }

        AlertDialog.Builder(context)
            .setTitle("TOTP Generator")
            .setView(binding.root)
            .setNegativeButton("Close") { _, _ ->
                running = false
                mainHandler.removeCallbacks(tickRunnable)
            }
            .setOnCancelListener {
                running = false
                mainHandler.removeCallbacks(tickRunnable)
            }
            .show()
    }

    private fun hotp(secret: ByteArray, counter: Long, digits: Int): String {
        val counterBytes = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            counterBytes[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)

        val offset = hash[hash.size - 1].toInt() and 0xF
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        var modulus = 1
        repeat(digits) { modulus *= 10 }
        val otp = binary % modulus
        return otp.toString().padStart(digits, '0')
    }

    /** RFC 4648 base32 decode (no external dependency needed for this). */
    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input.trim().uppercase().replace("=", "").replace(" ", "")
        val output = ByteArrayOutputStream()
        var buffer = 0L
        var bitsLeft = 0
        for (c in clean) {
            val value = alphabet.indexOf(c)
            if (value < 0) throw IllegalArgumentException("invalid character '$c'")
            buffer = (buffer shl 5) or value.toLong()
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.write(((buffer shr bitsLeft) and 0xFF).toInt())
            }
        }
        if (output.size() == 0) throw IllegalArgumentException("empty secret")
        return output.toByteArray()
    }
}
