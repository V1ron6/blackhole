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

/**
 * SSH terminal for ByteBandit mode - log into a personal or corporate server
 * and run commands. Credentials are held only in memory for this dialog's
 * session and are never written to Settings/SharedPreferences or disk; the
 * connection is torn down when the dialog closes.
 *
 * Host key verification: there's no persisted known_hosts store on a mobile
 * browser, so this uses trust-on-first-use-style promiscuous verification,
 * the same trade-off most mobile SSH clients make without a saved host key
 * database. That's a deliberate scope choice, not an oversight - be aware
 * this doesn't protect against a MITM the way a real known_hosts file would.
 */
object SSHTerminal {

    fun show(context: Context) {
        val binding = DialogSshBinding.inflate(LayoutInflater.from(context))
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
                    ssh.addHostKeyVerifier(PromiscuousVerifier())
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
}
