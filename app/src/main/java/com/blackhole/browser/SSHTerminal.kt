package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogSshBinding
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SSH terminal for ByteBandit mode - log into a personal or corporate server
 * and run commands. Credentials are held only in memory for this dialog's
 * session and are never written to Settings/SharedPreferences or disk; the
 * connection is torn down when the dialog closes.
 *
 * Host key verification uses trust-on-first-use: the first connection to a
 * host:port prompts you to accept and pin its key fingerprint (stored via
 * KnownHostsStore); every later connection compares silently against that
 * pinned value and refuses outright on a mismatch rather than prompting to
 * override - if a server's key legitimately rotates, use "Forget Saved
 * Host Key" to reset and re-pin deliberately. See KnownHostsStore's doc
 * comment for how the fingerprint is computed and its limits.
 */
object SSHTerminal {

    fun show(context: Context) {
        val binding = DialogSshBinding.inflate(LayoutInflater.from(context))
        val knownHosts = KnownHostsStore(context)
        var client: SSHClient? = null
        val output = StringBuilder()
        val mainHandler = Handler(context.mainLooper)

        fun appendOutput(line: String) {
            if (output.isNotEmpty()) output.append("\n")
            output.append(line)
            binding.terminalOutput.text = output.toString()
            binding.terminalScroll.post {
                binding.terminalScroll.fullScroll(View.FOCUS_DOWN)
            }
        }

        fun disconnect() {
            val toClose = client
            client = null
            if (toClose != null) {
                Thread {
                    try { toClose.disconnect() } catch (_: Exception) { }
                }.start()
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("SSH Terminal")
            .setView(binding.root)
            .setNegativeButton("Close") { _, _ -> disconnect() }
            .setOnCancelListener { disconnect() }
            .create()

        binding.btnForgetHostKey.setOnClickListener {
            val host = binding.inputHost.text.toString().trim()
            val port = binding.inputPort.text.toString().trim().toIntOrNull() ?: 22
            if (host.isBlank()) {
                appendOutput("Enter a host first.")
                return@setOnClickListener
            }
            knownHosts.forget(host, port)
            appendOutput("Forgot saved host key for $host:$port. Next connect will re-pin.")
        }

        binding.btnConnect.setOnClickListener {
            val host = binding.inputHost.text.toString().trim()
            val port = binding.inputPort.text.toString().trim().toIntOrNull() ?: 22
            val username = binding.inputUsername.text.toString().trim()
            val password = binding.inputPassword.text.toString()

            if (host.isBlank() || username.isBlank()) {
                appendOutput("Host and username are required.")
                return@setOnClickListener
            }

            appendOutput("Connecting to $username@$host:$port\u2026")
            binding.btnConnect.isEnabled = false

            Thread {
                try {
                    val ssh = SSHClient()
                    ssh.addHostKeyVerifier(pinningVerifier(context, mainHandler, knownHosts, ::appendOutput))
                    ssh.connectTimeout = 15_000
                    ssh.connect(host, port)
                    ssh.authPassword(username, password)
                    client = ssh
                    mainHandler.post {
                        appendOutput("Connected.")
                        binding.commandRow.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        appendOutput("Connection failed: ${e.message}")
                        binding.btnConnect.isEnabled = true
                    }
                }
            }.start()
        }

        binding.btnRunCommand.setOnClickListener {
            val cmd = binding.inputCommand.text.toString()
            val ssh = client
            if (cmd.isBlank() || ssh == null) return@setOnClickListener
            appendOutput("$ $cmd")
            binding.inputCommand.setText("")

            Thread {
                try {
                    ssh.startSession().use { session ->
                        val exec = session.exec(cmd)
                        val out = ByteArrayOutputStream()
                        exec.inputStream.copyTo(out)
                        val text = out.toString(Charsets.UTF_8.name()).ifBlank { "(no output)" }
                        mainHandler.post { appendOutput(text) }
                    }
                } catch (e: Exception) {
                    mainHandler.post { appendOutput("Command failed: ${e.message}") }
                }
            }.start()
        }

        dialog.show()
    }

    /**
     * Trust-on-first-use HostKeyVerifier. sshj calls verify() synchronously
     * during the handshake on the connecting thread (already a background
     * Thread here, never the main thread) - to prompt for a first-time key,
     * this blocks that thread on a CountDownLatch while the actual dialog
     * is shown via a post to the main thread, and resumes once the user taps
     * a button (or after a 2-minute timeout, which counts as "declined").
     */
    private fun pinningVerifier(
        context: Context,
        mainHandler: Handler,
        knownHosts: KnownHostsStore,
        appendOutput: (String) -> Unit
    ) = HostKeyVerifier { hostname, port, key ->
        val fingerprint = fingerprintOf(key)
        val stored = knownHosts.getFingerprint(hostname, port)

        when {
            stored == null -> {
                val trusted = promptTrustSync(context, mainHandler, hostname, port, fingerprint)
                if (trusted) {
                    knownHosts.saveFingerprint(hostname, port, fingerprint)
                    mainHandler.post { appendOutput("Pinned new host key for $hostname:$port.") }
                } else {
                    mainHandler.post { appendOutput("Host key rejected - connection will fail.") }
                }
                trusted
            }
            stored == fingerprint -> true
            else -> {
                mainHandler.post {
                    appendOutput(
                        "\u26A0 HOST KEY MISMATCH for $hostname:$port - refusing to connect. " +
                            "This could mean the server's key legitimately rotated, or it could be " +
                            "a MITM. If you're sure it's legitimate, use \"Forget Saved Host Key\" " +
                            "and reconnect to re-pin."
                    )
                }
                false
            }
        }
    }

    private fun promptTrustSync(
        context: Context,
        mainHandler: Handler,
        hostname: String,
        port: Int,
        fingerprint: String
    ): Boolean {
        val latch = CountDownLatch(1)
        var accepted = false
        mainHandler.post {
            AlertDialog.Builder(context)
                .setTitle("Unrecognized host key")
                .setMessage(
                    "First time connecting to $hostname:$port.\n\n" +
                        "Fingerprint:\n$fingerprint\n\n" +
                        "Only trust this if you have another way to confirm it's correct " +
                        "(e.g. the server admin gave it to you directly)."
                )
                .setCancelable(false)
                .setPositiveButton("Trust & Pin") { _, _ -> accepted = true; latch.countDown() }
                .setNegativeButton("Cancel") { _, _ -> accepted = false; latch.countDown() }
                .show()
        }
        latch.await(2, TimeUnit.MINUTES)
        return accepted
    }

    /**
     * SHA-256 over the key's encoded form. See KnownHostsStore's doc comment
     * for why this isn't the same as a standard OpenSSH fingerprint.
     */
    private fun fingerprintOf(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return digest.joinToString(":") { "%02x".format(it) }
    }
}
