package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogDirectoryBusterBinding
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small, deliberately lightweight directory/file fuzzer - gobuster's
 * basic idea (try a wordlist of paths against a target, report what isn't
 * a 404), not a reimplementation of gobuster itself. Kept RAM- and
 * bandwidth-conscious on purpose, since this runs on a phone, not a
 * pentest box:
 *
 * - Built-in wordlist is ~170 entries (a couple KB) - nowhere near a real
 *   SecLists wordlist, which can be 10s of MB / 100k+ lines and has no
 *   business running on mobile.
 * - A custom pasted wordlist is hard-capped at MAX_TOTAL_REQUESTS
 *   candidates (after factoring in extensions) - pasting something huge
 *   gets truncated with a visible warning, not silently processed.
 * - Concurrency is capped at MAX_CONCURRENCY simultaneous requests via a
 *   fixed thread pool, same pattern as the Port Scanner.
 * - Response bodies are never read into memory - only connection.responseCode
 *   is touched (which reads the status line/headers, not the body), then
 *   the connection is dropped immediately. A gobuster run holding onto
 *   response bodies for thousands of requests is exactly the kind of thing
 *   that would actually spike RAM; this avoids that entirely.
 * - Redirects are NOT auto-followed - a 3xx is itself a meaningful signal
 *   worth showing, not something to silently chase.
 */
object DirectoryBuster {

    private const val MAX_TOTAL_REQUESTS = 2000
    private const val MAX_CONCURRENCY = 8
    private const val CONNECT_TIMEOUT_MS = 4000

    fun show(context: Context, webView: WebView?) {
        val binding = DialogDirectoryBusterBinding.inflate(LayoutInflater.from(context))
        val defaultHost = try {
            webView?.url?.let { URL(it) }?.let { "${it.protocol}://${it.host}" }
        } catch (e: Exception) {
            null
        }
        binding.bustHost.setText(defaultHost ?: "")

        binding.bustWordlistGroup.setOnCheckedChangeListener { _, checkedId ->
            binding.bustCustomWordlist.visibility =
                if (checkedId == binding.radioCustomList.id) View.VISIBLE else View.GONE
        }

        binding.bustAuthorizedCheck.setOnCheckedChangeListener { _, checked ->
            binding.btnStartBust.isEnabled = checked
        }

        val mainHandler = Handler(context.mainLooper)

        binding.btnStartBust.setOnClickListener {
            if (!binding.bustAuthorizedCheck.isChecked) return@setOnClickListener
            val baseUrlText = binding.bustHost.text.toString().trim().trimEnd('/')
            if (baseUrlText.isBlank()) {
                binding.bustStatus.text = "Enter a target URL first."
                return@setOnClickListener
            }

            val words = if (binding.radioCustomList.isChecked) {
                binding.bustCustomWordlist.text.toString()
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            } else {
                loadBuiltinWordlist(context)
            }

            if (words.isEmpty()) {
                binding.bustStatus.text = "Wordlist is empty."
                return@setOnClickListener
            }

            val extensions = binding.bustExtensions.text.toString()
                .split(",")
                .map { it.trim().removePrefix(".") }
                .filter { it.isNotEmpty() }

            val candidates = buildCandidateList(words, extensions)
            val truncated = candidates.size > MAX_TOTAL_REQUESTS
            val finalList = candidates.take(MAX_TOTAL_REQUESTS)

            binding.btnStartBust.isEnabled = false
            binding.bustResults.text = ""
            binding.bustStatus.text = if (truncated) {
                "Scanning ${finalList.size} paths (truncated from ${candidates.size}) on $baseUrlText\u2026"
            } else {
                "Scanning ${finalList.size} paths on $baseUrlText\u2026"
            }

            Thread {
                runScan(baseUrlText, finalList, mainHandler, binding)
            }.start()
        }

        AlertDialog.Builder(context)
            .setTitle("Directory Buster")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun loadBuiltinWordlist(context: Context): List<String> {
        return try {
            context.resources.openRawResource(R.raw.common_paths).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildCandidateList(words: List<String>, extensions: List<String>): List<String> {
        if (extensions.isEmpty()) return words
        val result = mutableListOf<String>()
        words.forEach { word ->
            result.add(word)
            extensions.forEach { ext -> result.add("$word.$ext") }
        }
        return result
    }

    private fun runScan(
        baseUrl: String,
        paths: List<String>,
        mainHandler: Handler,
        binding: DialogDirectoryBusterBinding
    ) {
        val executor = Executors.newFixedThreadPool(MAX_CONCURRENCY)
        val scanned = AtomicInteger(0)
        val hits = AtomicInteger(0)
        val total = paths.size

        val futures = paths.map { path ->
            executor.submit {
                val result = probe(baseUrl, path)
                val done = scanned.incrementAndGet()
                if (result != null) {
                    hits.incrementAndGet()
                    mainHandler.post {
                        binding.bustResults.append("${result.second}  /${path}\n")
                    }
                }
                if (done % 25 == 0 || done == total) {
                    mainHandler.post {
                        binding.bustStatus.text = "Scanned $done/$total \u2022 ${hits.get()} hit(s)"
                    }
                }
            }
        }

        futures.forEach {
            try {
                it.get(CONNECT_TIMEOUT_MS + 3000L, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                // One slow/hung probe shouldn't block the rest of the scan.
            }
        }
        executor.shutdown()

        mainHandler.post {
            binding.bustStatus.text = "Done - ${hits.get()} hit(s) out of ${scanned.get()} scanned"
            binding.btnStartBust.isEnabled = true
        }
    }

    /**
     * Returns (path, statusCode) if the response wasn't a 404, or null if
     * it was (or the request failed outright - treated the same as "not
     * found" for this tool's purposes). Never reads the response body.
     */
    private fun probe(baseUrl: String, path: String): Pair<String, Int>? {
        return try {
            val connection = URL("$baseUrl/$path").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = CONNECT_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.connect()
            val code = connection.responseCode
            connection.disconnect()
            if (code == 404) null else path to code
        } catch (e: Exception) {
            null
        }
    }
}
