package com.blackhole.browser

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogCipherSolverBinding
import kotlin.math.pow

/**
 * Figures out an UNKNOWN encoding/cipher automatically - this is the
 * complement to Data Toolkit, which handles manual encode/decode once you
 * already know what you're dealing with.
 *
 * Auto-Decode cascades through hex -> base64 -> single-byte XOR brute force,
 * each scored by how "readable" (printable ASCII ratio) the result looks.
 * Caesar and Vigenere use classical frequency-analysis cryptanalysis
 * (chi-squared against English letter frequencies, index of coincidence for
 * Vigenere key-length detection) - standard CTF crypto-challenge technique,
 * not a lookup or wordlist attack.
 */
object CipherSolver {

    private val ENGLISH_FREQ = doubleArrayOf(
        8.17, 1.49, 2.78, 4.25, 12.70, 2.23, 2.02, 6.09, 6.97, 0.15,
        0.77, 4.03, 2.41, 6.75, 7.51, 1.93, 0.10, 5.99, 6.33, 9.06,
        2.76, 0.98, 2.36, 0.15, 1.97, 0.07
    ) // A..Z, percent

    fun show(context: Context) {
        val binding = DialogCipherSolverBinding.inflate(LayoutInflater.from(context))

        binding.btnAutoDecode.setOnClickListener {
            val input = binding.cipherInput.text.toString().trim()
            binding.autoDecodeResult.text = if (input.isEmpty()) "Enter some text first." else autoDecode(input)
        }
        binding.btnSolveCaesar.setOnClickListener {
            val input = binding.cipherInput.text.toString()
            binding.caesarResult.text = if (input.isBlank()) "Enter some text first." else solveCaesar(input)
        }
        binding.btnSolveVigenere.setOnClickListener {
            val input = binding.cipherInput.text.toString()
            binding.vigenereResult.text = if (input.isBlank()) "Enter some text first." else solveVigenere(input)
        }

        AlertDialog.Builder(context)
            .setTitle("Cipher Solver")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }

    // --- Auto-Decode: hex -> base64 -> XOR brute force -------------------

    private fun autoDecode(input: String): String {
        val hexRegex = Regex("^[A-Fa-f0-9\\s]+$")
        val base64Regex = Regex("^[A-Za-z0-9+/\\s]+={0,2}$")

        if (hexRegex.matches(input) && input.replace(Regex("\\s"), "").length % 2 == 0) {
            try {
                val clean = input.replace(Regex("\\s"), "")
                val bytes = ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                val text = String(bytes, Charsets.UTF_8)
                if (printableRatio(text) > 0.85) {
                    return "Detected: hex\nDecoded: $text"
                }
            } catch (_: Exception) { }
        }

        if (base64Regex.matches(input) && input.replace(Regex("\\s"), "").length % 4 == 0) {
            try {
                val bytes = android.util.Base64.decode(input.trim(), android.util.Base64.DEFAULT)
                val text = String(bytes, Charsets.UTF_8)
                if (printableRatio(text) > 0.85) {
                    return "Detected: base64\nDecoded: $text"
                }
            } catch (_: Exception) { }
        }

        // Single-byte XOR brute force, scored by printable-ASCII ratio.
        // Works on the raw bytes of the input text itself (common CTF setup:
        // a hex/raw string that's XOR'd with one repeating byte).
        val rawBytes = if (hexRegex.matches(input) && input.replace(Regex("\\s"), "").length % 2 == 0) {
            val clean = input.replace(Regex("\\s"), "")
            ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } else {
            input.toByteArray(Charsets.UTF_8)
        }

        val candidates = (0..255).map { key ->
            val decoded = ByteArray(rawBytes.size) { i -> (rawBytes[i].toInt() xor key).toByte() }
            val text = try { String(decoded, Charsets.UTF_8) } catch (_: Exception) { "" }
            Triple(key, text, printableRatio(text))
        }.filter { it.third > 0.9 }
            .sortedByDescending { it.third }
            .take(5)

        if (candidates.isEmpty()) {
            return "No clear hex/base64/single-byte-XOR encoding detected.\nTry ROT13 manually, or check Hash Identifier if this might be a hash instead."
        }

        return "No hex/base64 match. Top single-byte XOR candidates:\n\n" +
            candidates.joinToString("\n\n") { (key, text, _) -> "Key 0x%02x: %s".format(key, text.take(200)) }
    }

    private fun printableRatio(text: String): Double {
        if (text.isEmpty()) return 0.0
        val printable = text.count { it.code in 32..126 || it == '\n' || it == '\t' }
        return printable.toDouble() / text.length
    }

    // --- Caesar ------------------------------------------------------------

    private fun caesarShift(text: String, shift: Int): String = text.map { c ->
        when {
            c in 'A'..'Z' -> 'A' + ((c - 'A' - shift + 26) % 26)
            c in 'a'..'z' -> 'a' + ((c - 'a' - shift + 26) % 26)
            else -> c
        }
    }.joinToString("")

    private fun chiSquared(text: String): Double {
        val counts = IntArray(26)
        var total = 0
        for (c in text) {
            if (c.isLetter()) {
                counts[c.uppercaseChar() - 'A']++
                total++
            }
        }
        if (total == 0) return Double.MAX_VALUE
        var chi = 0.0
        for (i in 0..25) {
            val expected = ENGLISH_FREQ[i] / 100.0 * total
            if (expected > 0) chi += (counts[i] - expected).pow(2) / expected
        }
        return chi
    }

    private fun solveCaesar(input: String): String {
        val best = (0..25).map { shift -> shift to caesarShift(input, shift) }
            .minByOrNull { (_, decoded) -> chiSquared(decoded) }
            ?: return "Could not analyze input."
        return "Best guess - shift ${best.first}:\n${best.second}\n\n(All 26 shifts are equally one tap away if this one's wrong - rerun won't help since it's deterministic; try reading nearby shifts manually if needed.)"
    }

    // --- Vigenere ------------------------------------------------------------

    private fun indexOfCoincidence(letters: List<Int>): Double {
        if (letters.size < 2) return 0.0
        val counts = IntArray(26)
        letters.forEach { counts[it]++ }
        val n = letters.size
        val numerator = counts.sumOf { it.toLong() * (it - 1) }
        return numerator.toDouble() / (n.toLong() * (n - 1))
    }

    private fun solveVigenere(input: String): String {
        val letterIndices = input.filter { it.isLetter() }.map { it.uppercaseChar() - 'A' }
        if (letterIndices.size < 20) {
            return "Need at least ~20 letters for reliable key-length detection; input is too short."
        }

        var bestLen = 1
        var bestIc = 0.0
        for (len in 1..minOf(20, letterIndices.size / 10)) {
            val groups = (0 until len).map { col -> letterIndices.filterIndexed { i, _ -> i % len == col } }
            val avgIc = groups.map { indexOfCoincidence(it) }.average()
            if (avgIc > bestIc) {
                bestIc = avgIc
                bestLen = len
            }
        }

        if (bestIc < 0.045) {
            return "No clear repeating-key pattern found (best IC ${"%.3f".format(bestIc)} at length $bestLen) - this may not be Vigenere, or the ciphertext is too short/unusual."
        }

        // Per-column Caesar solve to recover each key letter.
        val key = StringBuilder()
        for (col in 0 until bestLen) {
            val columnLetters = letterIndices.filterIndexed { i, _ -> i % bestLen == col }
            val columnText = columnLetters.joinToString("") { ('A' + it).toString() }
            val shift = (0..25).minByOrNull { s -> chiSquared(caesarShift(columnText, s)) } ?: 0
            key.append('A' + shift)
        }

        val keyStr = key.toString()
        val decrypted = StringBuilder()
        var keyIndex = 0
        for (c in input) {
            if (c.isLetter()) {
                val shift = keyStr[keyIndex % keyStr.length] - 'A'
                decrypted.append(caesarShift(c.toString(), shift))
                keyIndex++
            } else {
                decrypted.append(c)
            }
        }

        return "Key length: $bestLen (IC ${"%.3f".format(bestIc)})\nGuessed key: $keyStr\n\nDecrypted:\n$decrypted"
    }
}
