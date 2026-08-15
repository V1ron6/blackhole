package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogSshBinding
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SSH terminal for ByteBandit mode - log into a personal or corporate server
 * and run commands. Credentials are held only in memory for this dialog's
 * session and are never written to Settings/SharedPreferences or disk; the
 * connection is torn down when the dialog closes.
 *
 * Host key verification uses trust-on-first-use, implemented as an
 * anonymous `object : HostKeyVerifier` (explicit class implementation, not
 * a SAM lambda) - HostKeyVerifier.verify(hostname, port, key) hands us the
 * server's PublicKey directly as a parameter, so there's no need to fetch
 * it separately after connecting. First connection to a host:port prompts
 * to accept and pin its fingerprint (via KnownHostsStore); later
 * connections compare silently and refuse outright on a mismatch - see
 * KnownHostsStore's doc comment for how the fingerprint is computed.
 */
object SSHTerminal {

    init {
        // Android ships its own limited "BC" security provider that's missing
        // algorithms sshj needs (X25519 key exchange in particular) - causes
        // "no such algorithm: X25519 for provider BC" even though the real,
        // full BouncyCastle library is already on the classpath via sshj's
        // own transitive dependency. It's just not the ACTIVE "BC" provider
        // until we swap it in explicitly. Must happen before any connection
        // attempt, hence doing it here rather than inside connect().
        try {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (e: Exception) {
            // Non-fatal - worst case, algorithms Android's own BC already
            // supports still work; only the newer ones would be affected.
        }
    }

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

            val verifier = object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    val fingerprint = fingerprintOf(key)
                    val stored = knownHosts.getFingerprint(hostname, port)
                    return when {
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
                                        "This could mean the server's key legitimately rotated, or it could " +
                                        "be a MITM. If you're sure it's legitimate, use \"Forget Saved Host " +
                                        "Key\" and reconnect to re-pin."
                                )
                            }
                            false
                        }
                    }
                }

                // sshj's HostKeyVerifier has a second abstract method beyond verify()
                // - used to hint which key algorithms an existing known_hosts entry
                // would match, so the client can prefer negotiating those. We don't
                // keep a known_hosts file (KnownHostsStore is a simple fingerprint
                // map, not algorithm-aware), so there's nothing to hint - an empty
                // list is the correct, safe answer, not a placeholder.
                override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> {
                    return mutableListOf()
                }
            }

            Thread {
                try {
                    val ssh = SSHClient()
                    ssh.addHostKeyVerifier(verifier)
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

