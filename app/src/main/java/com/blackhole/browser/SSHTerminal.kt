package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogSshBinding
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
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
 * Host key verification uses trust-on-first-use, implemented as a manual
 * post-connect check rather than a custom HostKeyVerifier: sshj's
 * HostKeyVerifier in this version isn't a plain single-method interface, so
 * a Kotlin SAM-lambda against it doesn't compile cleanly. Instead, the
 * transport connects using sshj's built-in PromiscuousVerifier (which
 * accepts any key so the handshake always completes), and immediately
 * after connecting - before any authentication - the negotiated key is
 * pulled from ssh.transport.hostKey and checked against KnownHostsStore
 * ourselves. Functionally the same TOFU behavior, just implemented one
 * layer up from the library's own verifier hook.
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
                var ssh: SSHClient? = null
                try {
                    ssh = SSHClient()
                    ssh.addHostKeyVerifier(PromiscuousVerifier())
                    ssh.connectTimeout = 15_000
                    ssh.connect(host, port)

                    val hostKey: PublicKey = ssh.transport.hostKey
                    val fingerprint = fingerprintOf(hostKey)
                    val stored = knownHosts.getFingerprint(host, port)

                    val approved = when {
                        stored == null -> {
                            val trusted = promptTrustSync(context, mainHandler, host, port, fingerprint)
                            if (trusted) {
                                knownHosts.saveFingerprint(host, port, fingerprint)
                                mainHandler.post { appendOutput("Pinned new host key for $host:$port.") }
                            } else {
                                mainHandler.post { appendOutput("Host key rejected - disconnecting.") }
                            }
                            trusted
                        }
                        stored == fingerprint -> true
                        else -> {
                            mainHandler.post {
                                appendOutput(
                                    "\u26A0 HOST KEY MISMATCH for $host:$port - refusing to connect. " +
                                        "This could mean the server's key legitimately rotated, or it " +
                                        "could be a MITM. If you're sure it's legitimate, use \"Forget " +
                                        "Saved Host Key\" and reconnect to re-pin."
                                )
                            }
                            false
                        }
                    }

                    if (!approved) {
                        ssh.disconnect()
                        mainHandler.post { binding.btnConnect.isEnabled = true }
                        return@Thread
                    }

                    ssh.authPassword(username, password)
                    client = ssh
                    mainHandler.post {
                        appendOutput("Connected.")
                        binding.commandRow.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    try { ssh?.disconnect() } catch (_: Exception) { }
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
