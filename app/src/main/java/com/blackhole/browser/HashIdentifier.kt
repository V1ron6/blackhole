package com.blackhole.browser

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogHashIdentifierBinding

/**
 * Guesses a hash's algorithm from its length, charset and prefix. This is a
 * local, offline heuristic - not a lookup table and not a cracker. Several
 * algorithms share a length (MD5/NTLM/MD4 are all 32 hex chars), so those
 * are reported together as candidates rather than a single answer.
 *
 * `identify()` is exposed as a standalone function (not just wired into the
 * dialog) so JtR-lite can call it directly later to auto-select a hash type
 * instead of asking the user to pick one.
 */
object HashIdentifier {

    data class Candidate(val name: String, val note: String)

    private val HEX = Regex("^[A-Fa-f0-9]+$")
    private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")
    private val JWT = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    fun show(context: Context) {
        val binding = DialogHashIdentifierBinding.inflate(LayoutInflater.from(context))

        binding.btnIdentifyHash.setOnClickListener {
            val input = binding.hashInput.text.toString().trim()
            if (input.isEmpty()) {
                binding.hashResults.text = "Enter a hash first."
                return@setOnClickListener
            }

            val candidates = identify(input)
            binding.hashResults.text = if (candidates.isEmpty()) {
                "No match - not a recognized hash format (hex/base64/common prefix)."
            } else {
                candidates.joinToString("\n\n") { "${it.name}\n${it.note}" }
            }
        }

        AlertDialog.Builder(context)
            .setTitle("Hash Identifier")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }

    /** Returns likely candidates for [raw], most specific/confident first. */
    fun identify(raw: String): List<Candidate> {
        val input = raw.trim()
        if (input.isEmpty()) return emptyList()

        // secretsdump-style "user:rid:lm:nt:::" lines - pull the two 32-hex
        // fields out and identify them individually rather than the whole line.
        if (input.contains(":")) {
            val fields = input.split(":").map { it.trim() }
            val hexFields = fields.filter { it.length == 32 && HEX.matches(it) }
            if (hexFields.size >= 2) {
                val out = mutableListOf<Candidate>()
                val lm = hexFields[0]
                val nt = hexFields[1]
                out += Candidate(
                    "Windows LM hash",
                    if (lm.equals("aad3b435b51404eeaad3b435b51404ee", ignoreCase = true))
                        "Field 1 ($lm) - empty/blank password half (the standard LM 'no password' value)."
                    else "Field 1 ($lm)."
                )
                out += Candidate("Windows NTLM hash", "Field 2 ($nt) - crack this one, LM is usually a dead end on modern systems.")
                return out
            }
        }

        // Unambiguous prefix-based formats.
        prefixMatch(input)?.let { return listOf(it) }

        if (JWT.matches(input)) {
            return listOf(Candidate("JSON Web Token (JWT)", "Not a hash - this is a signed token. Use the JWT Decoder tool instead."))
        }

        if (HEX.matches(input)) {
            return hexLengthCandidates(input.length).ifEmpty {
                listOf(Candidate("Unknown hex string", "${input.length} hex chars doesn't match a common hash length."))
            }
        }

        if (BASE64.matches(input) && input.length % 4 == 0 && input.length >= 8) {
            return listOf(
                Candidate(
                    "Base64-encoded data",
                    "Charset/length fit base64, not a hash directly. Decode it (Data Toolkit) and run Identify again on the result - it may reveal hex bytes or a salted hash structure."
                )
            )
        }

        return emptyList()
    }

    private fun prefixMatch(input: String): Candidate? = when {
        input.startsWith("\$2a\$") || input.startsWith("\$2b\$") ||
            input.startsWith("\$2x\$") || input.startsWith("\$2y\$") ->
            Candidate("bcrypt", "Modern adaptive hash. Cracking is slow by design - small wordlists only.")
        input.startsWith("\$argon2id\$") -> Candidate("Argon2id", "Memory-hard KDF. Very slow to brute force on mobile hardware.")
        input.startsWith("\$argon2i\$") -> Candidate("Argon2i", "Memory-hard KDF. Very slow to brute force on mobile hardware.")
        input.startsWith("\$argon2d\$") -> Candidate("Argon2d", "Memory-hard KDF. Very slow to brute force on mobile hardware.")
        input.startsWith("\$6\$") -> Candidate("SHA-512 crypt (Unix /etc/shadow)", "Salted, multi-round. Dictionary attack only.")
        input.startsWith("\$5\$") -> Candidate("SHA-256 crypt (Unix /etc/shadow)", "Salted, multi-round. Dictionary attack only.")
        input.startsWith("\$1\$") -> Candidate("MD5 crypt (Unix /etc/shadow, 'apr1')", "Old format, salted but fast - dictionary + rules is viable.")
        input.startsWith("\$P\$") || input.startsWith("\$H\$") ->
            Candidate("phpBB3 / WordPress (phpass)", "Salted MD5-based, thousands of rounds.")
        input.startsWith("*") && input.length == 41 && HEX.matches(input.substring(1)) ->
            Candidate("MySQL 4.1+/5.x (SHA1-based)", "Strip the leading '*' and treat the rest as SHA1 of SHA1 of the password.")
        else -> null
    }

    private fun hexLengthCandidates(len: Int): List<Candidate> = when (len) {
        8 -> listOf(Candidate("CRC32", "Checksum, not a password hash - not crackable in the usual sense, just reversible by brute force over small inputs."))
        16 -> listOf(Candidate("MySQL323 (old MySQL < 4.1)", "Weak legacy hash, fast to crack."))
        32 -> listOf(
            Candidate("MD5", "Fast hash, large rainbow tables exist. Most common 32-hex-char guess."),
            Candidate("NTLM", "Windows password hash. Same length as MD5 - context (SAM dump, secretsdump output) usually tells them apart."),
            Candidate("MD4", "Rare on its own; NTLM is MD4 internally."),
            Candidate("Windows LM hash (single half)", "Only if this came from a LM hash - usually seen as two halves, see the colon-separated check above.")
        )
        40 -> listOf(
            Candidate("SHA-1", "Fast hash, common in older systems and Git."),
            Candidate("MySQL5 (SHA1-based, no leading *)", "If this came from a MySQL user table, it's SHA1(SHA1(password))."),
            Candidate("RIPEMD-160", "Less common, seen in some crypto contexts.")
        )
        56 -> listOf(Candidate("SHA-224", "Less common SHA-2 variant."), Candidate("SHA3-224", "Keccak family variant."))
        64 -> listOf(
            Candidate("SHA-256", "Most common 64-hex-char hash today."),
            Candidate("SHA3-256", "Keccak family variant."),
            Candidate("BLAKE2s", "Fast modern hash, occasionally used instead of SHA-256.")
        )
        96 -> listOf(Candidate("SHA-384", "Less common SHA-2 variant."), Candidate("SHA3-384", "Keccak family variant."))
        128 -> listOf(
            Candidate("SHA-512", "Most common 128-hex-char hash."),
            Candidate("SHA3-512", "Keccak family variant."),
            Candidate("Whirlpool", "Less common, seen in some legacy systems.")
        )
        else -> emptyList()
    }
}
