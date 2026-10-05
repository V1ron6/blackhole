package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogHashCrackerBinding
import org.bouncycastle.crypto.digests.MD4Digest
import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import java.security.MessageDigest

/**
 * "JtR-lite" - offline dictionary + basic-rule hash cracker. This is NOT a
 * port of John the Ripper: no GPU, no real rule engine, no format auto-detect
 * beyond what HashIdentifier already does. It's a dictionary attack against
 * a small built-in wordlist (or a pasted custom one), with a few common
 * mutations applied - which covers the large majority of CTF "crack this
 * hash" challenges, where the answer is a dictionary word with a tweak.
 *
 * Supported types are deliberately limited to ones crackable with a plain
 * digest comparison (or, for bcrypt, BouncyCastle's salt-aware checker) -
 * salted Unix crypt formats ($1$/$5$/$6$) aren't implemented yet since each
 * needs its own salt-extraction + algorithm, and HashIdentifier already
 * flags those to the user rather than silently failing here.
 */
object HashCracker {

    private data class HashType(
        val label: String,
        val verify: (candidate: String, target: String) -> Boolean
    )

    // Fast hashes: cap generous, these run in a tight loop on-device at
    // real speed. bcrypt is deliberately ~1000x+ slower per attempt by
    // design, so its cap is far lower - this is intentional_slow, not a bug.
    private const val MAX_ATTEMPTS_FAST = 150_000
    private const val MAX_ATTEMPTS_BCRYPT = 800
    private const val PROGRESS_EVERY = 500

    @Volatile private var running = false

    private val hashTypes = listOf(
        HashType("MD5") { c, t -> digestHex("MD5", c).equals(t, ignoreCase = true) },
        HashType("SHA-1") { c, t -> digestHex("SHA-1", c).equals(t, ignoreCase = true) },
        HashType("SHA-256") { c, t -> digestHex("SHA-256", c).equals(t, ignoreCase = true) },
        HashType("SHA-512") { c, t -> digestHex("SHA-512", c).equals(t, ignoreCase = true) },
        HashType("NTLM") { c, t -> ntlmHex(c).equals(t, ignoreCase = true) },
        HashType("bcrypt") { c, t ->
            try { OpenBSDBCrypt.checkPassword(t, c.toCharArray()) } catch (_: Exception) { false }
        }
    )

    fun show(context: Context) {
        val binding = DialogHashCrackerBinding.inflate(LayoutInflater.from(context))
        val mainHandler = Handler(context.mainLooper)

        binding.crackHashType.adapter = ArrayAdapter(
            context,
            R.layout.spinner_item_dark,
            hashTypes.map { it.label }
        ).apply { setDropDownViewResource(R.layout.spinner_dropdown_item_dark) }

        binding.crackWordlistGroup.setOnCheckedChangeListener { _, checkedId ->
            binding.crackCustomWordlist.visibility =
                if (checkedId == binding.radioCrackCustomList.id) View.VISIBLE else View.GONE
        }

        // Pre-select the type HashIdentifier thinks is most likely once a
        // hash is pasted in, so the user isn't guessing blind.
        binding.crackHashInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val guess = HashIdentifier.identify(binding.crackHashInput.text.toString())
                .map { it.name }
                .firstOrNull { guessName -> hashTypes.any { it.label.equals(guessName, ignoreCase = true) } }
            if (guess != null) {
                val index = hashTypes.indexOfFirst { it.label.equals(guess, ignoreCase = true) }
                if (index >= 0) binding.crackHashType.setSelection(index)
            }
        }

        binding.btnStartCrack.setOnClickListener {
            if (running) {
                running = false
                return@setOnClickListener
            }

            val target = binding.crackHashInput.text.toString().trim()
            if (target.isEmpty()) {
                binding.crackStatus.text = "Enter a hash first."
                return@setOnClickListener
            }

            val type = hashTypes[binding.crackHashType.selectedItemPosition]
            val words = if (binding.radioCrackCustomList.isChecked) {
                binding.crackCustomWordlist.text.toString()
                    .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            } else {
                loadBuiltinWordlist(context)
            }
            if (words.isEmpty()) {
                binding.crackStatus.text = "Wordlist is empty."
                return@setOnClickListener
            }

            val rules = MutationRules(
                capitalize = binding.chkCapitalize.isChecked,
                leet = binding.chkLeet.isChecked,
                appendDigits = binding.chkAppendDigits.isChecked
            )

            val maxAttempts = if (type.label == "bcrypt") MAX_ATTEMPTS_BCRYPT else MAX_ATTEMPTS_FAST
            running = true
            binding.btnStartCrack.text = "Stop"
            binding.crackResult.text = ""
            binding.crackStatus.text = "Cracking\u2026"

            Thread {
                runCrack(target, type, words, rules, maxAttempts, mainHandler, binding)
            }.start()
        }

        AlertDialog.Builder(context)
            .setTitle("Hash Cracker (JtR-lite)")
            .setView(binding.root)
            .setNegativeButton("Close") { _, _ -> running = false }
            .setOnCancelListener { running = false }
            .show()
    }

    private data class MutationRules(val capitalize: Boolean, val leet: Boolean, val appendDigits: Boolean)

    /** Lazily-generated candidates - never materializes the full expanded list in memory. */
    private fun candidateSequence(words: List<String>, rules: MutationRules): Sequence<String> = sequence {
        for (word in words) {
            yield(word)
            if (rules.capitalize) yield(word.replaceFirstChar { it.uppercase() })
            if (rules.leet) yield(leetify(word))
            if (rules.appendDigits) {
                for (n in 0..99) yield("$word$n")
            }
        }
    }

    private fun leetify(word: String): String = word
        .replace('a', '4').replace('A', '4')
        .replace('e', '3').replace('E', '3')
        .replace('i', '1').replace('I', '1')
        .replace('o', '0').replace('O', '0')
        .replace('s', '5').replace('S', '5')

    private fun runCrack(
        target: String,
        type: HashType,
        words: List<String>,
        rules: MutationRules,
        maxAttempts: Int,
        mainHandler: Handler,
        binding: DialogHashCrackerBinding
    ) {
        var attempts = 0
        var found: String? = null

        for (candidate in candidateSequence(words, rules)) {
            if (!running || attempts >= maxAttempts) break
            attempts++
            if (type.verify(candidate, target)) {
                found = candidate
                break
            }
            if (attempts % PROGRESS_EVERY == 0) {
                mainHandler.post {
                    binding.crackStatus.text = "Tried $attempts candidate(s)\u2026"
                }
            }
        }

        val wasStopped = !running
        running = false

        mainHandler.post {
            binding.btnStartCrack.text = "Start Cracking"
            when {
                found != null -> {
                    binding.crackStatus.text = "Found after $attempts attempt(s)."
                    binding.crackResult.text = "Password: $found"
                }
                wasStopped -> {
                    binding.crackStatus.text = "Stopped after $attempts attempt(s)."
                    binding.crackResult.text = ""
                }
                else -> {
                    binding.crackStatus.text = "Not found in $attempts attempt(s)."
                    binding.crackResult.text = ""
                }
            }
        }
    }

    private fun loadBuiltinWordlist(context: Context): List<String> {
        return try {
            context.resources.openRawResource(R.raw.common_passwords).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun digestHex(algorithm: String, input: String): String {
        val digest = MessageDigest.getInstance(algorithm).digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** NTLM = MD4 of the UTF-16LE encoding of the password (no salt). */
    private fun ntlmHex(input: String): String {
        val md4 = MD4Digest()
        val bytes = input.toByteArray(Charsets.UTF_16LE)
        md4.update(bytes, 0, bytes.size)
        val out = ByteArray(md4.digestSize)
        md4.doFinal(out, 0)
        return out.joinToString("") { "%02x".format(it) }
    }
}
