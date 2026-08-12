package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogPortScannerBinding
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Simple TCP connect-scan against a small set of common ports. The Scan
 * button stays disabled until the person explicitly checks the
 * authorization box - this isn't a legal safeguard, just a UI speed bump
 * against scanning something by accident or habit.
 *
 * Uses a short-lived thread pool for concurrency so a full scan doesn't
 * take (port count * timeout) seconds sequentially; each socket attempt
 * still has its own timeout so a filtered/dropping host doesn't hang
 * the whole scan.
 */
object PortScanner {

    private val commonPorts = listOf(
        21, 22, 23, 25, 53, 80, 110, 111, 135, 139, 143, 443, 445,
        587, 993, 995, 1433, 1521, 3000, 3306, 3389, 5432, 5900,
        6379, 8000, 8080, 8443, 9200, 27017
    )
    private const val CONNECT_TIMEOUT_MS = 600
    private const val MAX_CONCURRENCY = 12

    fun show(context: Context, webView: WebView?) {
        val binding = DialogPortScannerBinding.inflate(LayoutInflater.from(context))
        val defaultHost = try {
            webView?.url?.let { URL(it).host }
        } catch (e: Exception) {
            null
        }
        binding.scanHost.setText(defaultHost ?: "")

        binding.scanAuthorizedCheck.setOnCheckedChangeListener { _, checked ->
            binding.btnStartScan.isEnabled = checked
        }

        val mainHandler = Handler(context.mainLooper)

        binding.btnStartScan.setOnClickListener {
            val host = binding.scanHost.text.toString().trim()
            if (host.isBlank()) {
                binding.scanStatus.text = "Enter a host first."
                return@setOnClickListener
            }
            if (!binding.scanAuthorizedCheck.isChecked) return@setOnClickListener

            binding.btnStartScan.isEnabled = false
            binding.scanStatus.text = "Scanning ${commonPorts.size} ports on $host\u2026"
            binding.scanResults.text = ""

            Thread {
                val open = runScan(host)
                mainHandler.post {
                    binding.scanStatus.text = "Done - ${open.size} open port(s) found"
                    binding.scanResults.text = if (open.isEmpty()) {
                        "(none of the scanned ports responded)"
                    } else {
                        open.sorted().joinToString("\n") { "$it/tcp open" }
                    }
                    binding.btnStartScan.isEnabled = true
                }
            }.start()
        }

        AlertDialog.Builder(context)
            .setTitle("Port Scanner")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun runScan(host: String): List<Int> {
        val executor = Executors.newFixedThreadPool(MAX_CONCURRENCY)
        val open = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val futures = commonPorts.map { port ->
            executor.submit {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                        open.add(port)
                    }
                } catch (e: Exception) {
                    // Closed, filtered, or timed out - not open. Nothing to record.
                }
            }
        }
        futures.forEach {
            try {
                it.get(CONNECT_TIMEOUT_MS + 2000L, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                // Individual probe timed out beyond its own socket timeout - skip it,
                // don't let one slow probe abort the rest of the scan.
            }
        }
        executor.shutdown()
        return open
    }
}
