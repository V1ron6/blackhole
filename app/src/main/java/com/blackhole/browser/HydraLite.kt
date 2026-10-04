package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogHydraBinding
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Hydra-lite" - a small credential brute forcer covering the protocols
 * that come up most in CTFs: HTTP Basic Auth, HTTP POST login forms, FTP,
 * and SSH. Not a port of real Hydra - no GPU, no 50+ protocol modules, no
 * tuning knobs beyond what's here.
 *
 * Same authorization-gate + hard-cap philosophy as Directory Buster and
 * Port Scanner, but tighter: this attacks a LIVE service over the network
 * (real accounts can get locked out), so the cap and concurrency are both
 * lower, and it stops at the first working credential pair rather than
 * enumerating everything.
 */
object HydraLite {

    init {
        // Same fix as SSHTerminal - Android's built-in "BC" provider is
        // missing algorithms sshj needs (X25519 in particular). Must run
        // before any SSH attempt; duplicated here rather than depending on
        // SSHTerminal having been opened first in this session.
        try {
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        } catch (e: Exception) {
            // Non-fatal - worst case, only the newer algorithms are affected.
        }
    }

    private const val MAX_TOTAL_ATTEMPTS = 500
    private const val MAX_CONCURRENCY = 5
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val MAX_RESPONSE_BYTES = 4096

    @Volatile private var running = false

    private val protocols = listOf("HTTP Basic Auth", "HTTP POST Form", "FTP", "SSH")

    fun show(context: Context, webView: WebView?) {
        val binding = DialogHydraBinding.inflate(LayoutInflater.from(context))
        val mainHandler = Handler(context.mainLooper)

        binding.hydraProtocol.adapter = ArrayAdapter(
            context, R.layout.spinner_item_dark, protocols
        ).apply { setDropDownViewResource(R.layout.spinner_dropdown_item_dark) }

        val defaultTarget = try { webView?.url } catch (e: Exception) { null }
        binding.hydraTarget.setText(defaultTarget ?: "")

        fun updateFieldVisibility() {
            when (protocols[binding.hydraProtocol.selectedItemPosition]) {
                "HTTP Basic Auth" -> {
                    binding.hydraPort.visibility = View.GONE
                    binding.hydraFormFieldsGroup.visibility = View.GONE
                    binding.hydraTarget.hint = "https://example.com/admin"
                }
                "HTTP POST Form" -> {
                    binding.hydraPort.visibility = View.GONE
                    binding.hydraFormFieldsGroup.visibility = View.VISIBLE
                    binding.hydraTarget.hint = "https://example.com/login"
                }
                "FTP" -> {
                    binding.hydraPort.visibility = View.VISIBLE
                    binding.hydraPort.hint = "Port (default 21)"
                    binding.hydraFormFieldsGroup.visibility = View.GONE
                    binding.hydraTarget.hint = "ftp.example.com or IP"
                }
                "SSH" -> {
                    binding.hydraPort.visibility = View.VISIBLE
                    binding.hydraPort.hint = "Port (default 22)"
                    binding.hydraFormFieldsGroup.visibility = View.GONE
                    binding.hydraTarget.hint = "example.com or IP"
                }
            }
        }
        binding.hydraProtocol.post { updateFieldVisibility() }
        binding.hydraProtocol.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = updateFieldVisibility()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })

        binding.hydraUserListGroup.setOnCheckedChangeListener { _, checkedId ->
            binding.hydraCustomUsers.visibility =
                if (checkedId == binding.radioHydraCustomUsers.id) View.VISIBLE else View.GONE
        }
        binding.hydraPassListGroup.setOnCheckedChangeListener { _, checkedId ->
            binding.hydraCustomPass.visibility =
                if (checkedId == binding.radioHydraCustomPass.id) View.VISIBLE else View.GONE
        }
        binding.hydraAuthorizedCheck.setOnCheckedChangeListener { _, checked ->
            binding.btnStartHydra.isEnabled = checked
        }

        binding.btnStartHydra.setOnClickListener {
            if (running) {
                running = false
                return@setOnClickListener
            }
            if (!binding.hydraAuthorizedCheck.isChecked) return@setOnClickListener

            val target = binding.hydraTarget.text.toString().trim()
            if (target.isEmpty()) {
                binding.hydraStatus.text = "Enter a target first."
                return@setOnClickListener
            }
            val protocol = protocols[binding.hydraProtocol.selectedItemPosition]

            val users = if (binding.radioHydraCustomUsers.isChecked) {
                parseLines(binding.hydraCustomUsers.text.toString())
            } else loadRaw(context, R.raw.common_usernames)

            val passwords = if (binding.radioHydraCustomPass.isChecked) {
                parseLines(binding.hydraCustomPass.text.toString())
            } else loadRaw(context, R.raw.common_passwords)

            if (users.isEmpty() || passwords.isEmpty()) {
                binding.hydraStatus.text = "Username or password list is empty."
                return@setOnClickListener
            }

            val pairs = mutableListOf<Pair<String, String>>()
            outer@ for (u in users) for (p in passwords) {
                pairs.add(u to p)
                if (pairs.size >= MAX_TOTAL_ATTEMPTS) break@outer
            }
            val truncated = users.size.toLong() * passwords.size.toLong() > MAX_TOTAL_ATTEMPTS

            val port = binding.hydraPort.text.toString().trim().toIntOrNull()
            val userField = binding.hydraUserField.text.toString().trim().ifBlank { "username" }
            val passField = binding.hydraPassField.text.toString().trim().ifBlank { "password" }
            val failIndicator = binding.hydraFailIndicator.text.toString().trim()

            running = true
            binding.btnStartHydra.text = "Stop"
            binding.hydraResult.text = ""
            binding.hydraStatus.text = if (truncated)
                "Trying ${pairs.size} pairs (capped from ${users.size * passwords.size})\u2026"
            else "Trying ${pairs.size} pairs\u2026"

            Thread {
                runAttack(protocol, target, port, userField, passField, failIndicator, pairs, mainHandler, binding)
            }.start()
        }

        AlertDialog.Builder(context)
            .setTitle("Hydra-lite (Credential Brute Forcer)")
            .setView(binding.root)
            .setNegativeButton("Close") { _, _ -> running = false }
            .setOnCancelListener { running = false }
            .show()
    }

    private fun parseLines(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private fun loadRaw(context: Context, resId: Int): List<String> = try {
        context.resources.openRawResource(resId).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
    } catch (e: Exception) { emptyList() }

    private fun runAttack(
        protocol: String,
        target: String,
        port: Int?,
        userField: String,
        passField: String,
        failIndicator: String,
        pairs: List<Pair<String, String>>,
        mainHandler: Handler,
        binding: DialogHydraBinding
    ) {
        val executor = Executors.newFixedThreadPool(MAX_CONCURRENCY)
        val tried = AtomicInteger(0)
        val found = AtomicBoolean(false)
        var winner: Pair<String, String>? = null
        val total = pairs.size

        val futures = pairs.map { (user, pass) ->
            executor.submit {
                if (!running || found.get()) return@submit
                val success = try {
                    when (protocol) {
                        "HTTP Basic Auth" -> attemptHttpBasic(target, user, pass)
                        "HTTP POST Form" -> attemptHttpForm(target, userField, passField, failIndicator, user, pass)
                        "FTP" -> attemptFtp(target, port ?: 21, user, pass)
                        "SSH" -> attemptSsh(target, port ?: 22, user, pass)
                        else -> false
                    }
                } catch (e: Exception) { false }

                val done = tried.incrementAndGet()
                if (success && found.compareAndSet(false, true)) {
                    winner = user to pass
                    running = false
                }
                if (done % 10 == 0 || done == total || found.get()) {
                    mainHandler.post {
                        binding.hydraStatus.text = "Tried $done/$total\u2026"
                    }
                }
            }
        }

        futures.forEach {
            try {
                it.get(CONNECT_TIMEOUT_MS + 3000L, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                // One slow/hung attempt shouldn't block the rest.
            }
        }
        executor.shutdownNow()

        val wasStopped = !running && winner == null
        running = false

        mainHandler.post {
            binding.btnStartHydra.text = "Start"
            val w = winner
            when {
                w != null -> {
                    binding.hydraStatus.text = "Found after ${tried.get()} attempt(s)."
                    binding.hydraResult.text = "${w.first} : ${w.second}"
                }
                wasStopped -> {
                    binding.hydraStatus.text = "Stopped after ${tried.get()} attempt(s)."
                }
                else -> {
                    binding.hydraStatus.text = "No working credentials found in ${tried.get()} attempt(s)."
                }
            }
        }
    }

    // --- Protocol modules ------------------------------------------------

    private fun attemptHttpBasic(urlStr: String, user: String, pass: String): Boolean {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
            connection.setRequestProperty("Authorization", "Basic $token")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = CONNECT_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.connect()
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Mirrors real Hydra's http-post-form idea: POST the credentials, then
     * judge success by the ABSENCE of a known failure string in the body
     * (rather than assuming any particular success string/redirect, which
     * varies too much site to site to hardcode).
     */
    private fun attemptHttpForm(
        urlStr: String, userField: String, passField: String,
        failIndicator: String, user: String, pass: String
    ): Boolean {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            val body = "${URLEncoder.encode(userField, "UTF-8")}=${URLEncoder.encode(user, "UTF-8")}" +
                "&${URLEncoder.encode(passField, "UTF-8")}=${URLEncoder.encode(pass, "UTF-8")}"
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = CONNECT_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.connect()

            if (failIndicator.isBlank()) {
                // No indicator given - fall back to a plain 200-vs-everything-else
                // heuristic. Much less reliable; the UI hints this strongly.
                return connection.responseCode == 200
            }
            val stream = if (connection.responseCode in 200..399) connection.inputStream else connection.errorStream
            val bodyText = stream?.use { input ->
                val reader = BufferedReader(InputStreamReader(input))
                val buf = CharArray(MAX_RESPONSE_BYTES)
                val n = reader.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            } ?: ""
            !bodyText.contains(failIndicator, ignoreCase = true)
        } finally {
            connection.disconnect()
        }
    }

    /** Minimal FTP login (USER/PASS over a raw socket) - no data channel, just auth. */
    private fun attemptFtp(host: String, port: Int, user: String, pass: String): Boolean {
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = socket.getOutputStream()

            fun readResponseCode(): Int {
                val line = reader.readLine() ?: return -1
                return line.take(3).toIntOrNull() ?: -1
            }
            fun send(cmd: String) = writer.write("$cmd\r\n".toByteArray(Charsets.UTF_8)).also { writer.flush() }

            readResponseCode() // banner (220)
            send("USER $user")
            val userCode = readResponseCode()
            if (userCode == 230) return true // some servers accept user with no password
            if (userCode != 331) return false // not expecting a password - treat as fail
            send("PASS $pass")
            return readResponseCode() == 230
        }
    }

    private fun attemptSsh(host: String, port: Int, user: String, pass: String): Boolean {
        val ssh = SSHClient()
        return try {
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = CONNECT_TIMEOUT_MS
            ssh.connect(host, port)
            ssh.authPassword(user, pass)
            true
        } catch (e: Exception) {
            false
        } finally {
            try { ssh.disconnect() } catch (_: Exception) { }
        }
    }
}
