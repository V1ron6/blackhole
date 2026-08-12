package com.blackhole.browser

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.util.Base64
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogFaviconHashBinding
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches the active tab's host's /favicon.ico and computes two hashes:
 *
 * - A Shodan-style hash: mmh3 (MurmurHash3 x86_32, seed 0) over the
 *   base64-of-the-raw-bytes, matching the algorithm Shodan documents for
 *   its http.favicon.hash field - useful for pivoting to Shodan searches
 *   on shared favicons. This is a hand-rolled mmh3 implementation with no
 *   reference test vector available in this environment to verify against,
 *   so treat it as best-effort; it's included alongside a standard SHA-256
 *   below specifically so you have a verified-correct hash even if this one
 *   has a subtle bug.
 * - SHA-256 over the raw bytes: a standard, unambiguous hash for your own
 *   comparison purposes (e.g. "did this favicon change between visits").
 *
 * Only checks /favicon.ico directly - doesn't parse the page for a
 * <link rel="icon"> pointing somewhere else.
 */
object FaviconHash {

    fun show(context: Context, webView: WebView?) {
        val pageUrl = webView?.url
        val (scheme, host) = try {
            val u = URL(pageUrl)
            u.protocol to u.host
        } catch (e: Exception) {
            null to null
        }
        if (host.isNullOrBlank()) {
            Toast.makeText(context, "No page loaded to determine the host", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogFaviconHashBinding.inflate(LayoutInflater.from(context))
        val faviconUrl = "$scheme://$host/favicon.ico"
        binding.faviconTargetUrl.text = faviconUrl
        val mainHandler = Handler(context.mainLooper)

        AlertDialog.Builder(context)
            .setTitle("Favicon Hash")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()

        Thread {
            try {
                val connection = URL(faviconUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) {
                    connection.disconnect()
                    mainHandler.post { binding.faviconStatus.text = "HTTP $code - no favicon found at this path" }
                    return@Thread
                }
                val bytes = connection.inputStream.use { it.readBytes() }
                connection.disconnect()

                val base64 = Base64.encode(bytes, Base64.DEFAULT)
                val mmh3 = murmurHash3X86_32(base64, 0)
                val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }

                mainHandler.post {
                    binding.faviconStatus.text = "${bytes.size} bytes"
                    binding.faviconMmh3.text = mmh3.toString()
                    binding.faviconSha256.text = sha256
                    try {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) binding.faviconPreview.setImageBitmap(bitmap)
                    } catch (e: Exception) {
                        // Not a decodable image format (some sites serve .ico as raw
                        // multi-image ICO that Android's decoder can't parse) - hash
                        // still stands, just no preview.
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { binding.faviconStatus.text = "Fetch failed: ${e.message}" }
            }
        }.start()
    }

    /** MurmurHash3 x86_32, standard reference algorithm. */
    private fun murmurHash3X86_32(data: ByteArray, seed: Int): Int {
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593.toInt()
        val length = data.size
        val nblocks = length / 4
        var h1 = seed

        for (i in 0 until nblocks) {
            val offset = i * 4
            var k1 = (data[offset].toInt() and 0xff) or
                ((data[offset + 1].toInt() and 0xff) shl 8) or
                ((data[offset + 2].toInt() and 0xff) shl 16) or
                ((data[offset + 3].toInt() and 0xff) shl 24)
            k1 *= c1
            k1 = (k1 shl 15) or (k1 ushr 17)
            k1 *= c2
            h1 = h1 xor k1
            h1 = (h1 shl 13) or (h1 ushr 19)
            h1 = h1 * 5 + 0xe6546b64.toInt()
        }

        var k1 = 0
        val tailStart = nblocks * 4
        when (length and 3) {
            3 -> {
                k1 = k1 xor ((data[tailStart + 2].toInt() and 0xff) shl 16)
                k1 = k1 xor ((data[tailStart + 1].toInt() and 0xff) shl 8)
                k1 = k1 xor (data[tailStart].toInt() and 0xff)
                k1 *= c1; k1 = (k1 shl 15) or (k1 ushr 17); k1 *= c2
                h1 = h1 xor k1
            }
            2 -> {
                k1 = k1 xor ((data[tailStart + 1].toInt() and 0xff) shl 8)
                k1 = k1 xor (data[tailStart].toInt() and 0xff)
                k1 *= c1; k1 = (k1 shl 15) or (k1 ushr 17); k1 *= c2
                h1 = h1 xor k1
            }
            1 -> {
                k1 = k1 xor (data[tailStart].toInt() and 0xff)
                k1 *= c1; k1 = (k1 shl 15) or (k1 ushr 17); k1 *= c2
                h1 = h1 xor k1
            }
        }

        h1 = h1 xor length
        h1 = fmix32(h1)
        return h1
    }

    private fun fmix32(hIn: Int): Int {
        var h = hIn
        h = h xor (h ushr 16)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        return h
    }
}
