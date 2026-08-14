package com.blackhole.browser

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogRegexTesterBinding

/**
 * Tests a regex pattern against sample text, entirely local. Uses standard
 * java.util.regex (Pattern/Matcher) under the hood via Kotlin's Regex.
 */
object RegexTester {

    private const val MAX_MATCHES_SHOWN = 200

    fun show(context: Context) {
        val binding = DialogRegexTesterBinding.inflate(LayoutInflater.from(context))

        binding.btnRunRegex.setOnClickListener {
            runTest(context, binding)
        }

        AlertDialog.Builder(context)
            .setTitle("Regex Tester")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun runTest(context: Context, binding: DialogRegexTesterBinding) {
        val patternText = binding.regexPattern.text.toString()
        val sampleText = binding.regexSampleText.text.toString()

        if (patternText.isBlank()) {
            binding.regexStatus.text = "Enter a pattern first."
            binding.regexStatus.setTextColor(context.getColor(R.color.bh_warning))
            binding.regexMatches.text = ""
            return
        }

        val options = mutableSetOf<RegexOption>()
        if (binding.checkIgnoreCase.isChecked) options.add(RegexOption.IGNORE_CASE)
        if (binding.checkMultiline.isChecked) options.add(RegexOption.MULTILINE)
        if (binding.checkDotAll.isChecked) options.add(RegexOption.DOT_MATCHES_ALL)

        val regex = try {
            Regex(patternText, options)
        } catch (e: Exception) {
            binding.regexStatus.text = "\u2717 Invalid pattern: ${e.message}"
            binding.regexStatus.setTextColor(context.getColor(R.color.bh_danger))
            binding.regexMatches.text = ""
            return
        }

        val matches = regex.findAll(sampleText).toList()
        binding.regexStatus.text = "${matches.size} match${if (matches.size == 1) "" else "es"}"
        binding.regexStatus.setTextColor(
            context.getColor(if (matches.isEmpty()) R.color.bh_text_dim else R.color.bh_success)
        )

        binding.regexMatches.text = if (matches.isEmpty()) {
            "(no matches)"
        } else {
            matches.take(MAX_MATCHES_SHOWN).mapIndexed { index, match ->
                val groupsText = if (match.groups.size > 1) {
                    val groupValues = (1 until match.groups.size)
                        .map { i -> match.groups[i]?.value ?: "" }
                        .joinToString(", ")
                    " groups: [$groupValues]"
                } else ""
                "[$index] @${match.range.first}-${match.range.last}: \"${match.value}\"$groupsText"
            }.joinToString("\n") + if (matches.size > MAX_MATCHES_SHOWN) "\n\u2026 (${matches.size - MAX_MATCHES_SHOWN} more not shown)" else ""
        }
    }
}
