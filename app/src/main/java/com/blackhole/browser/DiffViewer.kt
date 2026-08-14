package com.blackhole.browser

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogDiffViewerBinding

/**
 * Line-based diff between two pasted blobs, computed locally with a classic
 * LCS (longest common subsequence) algorithm. That's O(n*m) in the number
 * of lines on each side, so very large inputs are capped rather than left
 * to hang the UI thread.
 */
object DiffViewer {

    private const val MAX_LINES = 2000

    private sealed class DiffLine {
        data class Same(val text: String) : DiffLine()
        data class Removed(val text: String) : DiffLine()
        data class Added(val text: String) : DiffLine()
    }

    fun show(context: Context) {
        val binding = DialogDiffViewerBinding.inflate(LayoutInflater.from(context))

        binding.btnRunDiff.setOnClickListener {
            val linesA = binding.diffInputA.text.toString().split("\n")
            val linesB = binding.diffInputB.text.toString().split("\n")

            if (linesA.size > MAX_LINES || linesB.size > MAX_LINES) {
                binding.diffStatus.text = "Too large to diff (limit $MAX_LINES lines per side)."
                binding.diffOutput.text = ""
                return@setOnClickListener
            }

            val diff = computeDiff(linesA, linesB)
            val added = diff.count { it is DiffLine.Added }
            val removed = diff.count { it is DiffLine.Removed }
            binding.diffStatus.text = "+$added / -$removed"

            binding.diffOutput.text = renderSpannable(context, diff)
        }

        AlertDialog.Builder(context)
            .setTitle("Diff Viewer")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Classic O(n*m) LCS-backtrack diff, line by line.
     */
    private fun computeDiff(a: List<String>, b: List<String>): List<DiffLine> {
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        val result = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    result.add(DiffLine.Same(a[i]))
                    i++; j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    result.add(DiffLine.Removed(a[i]))
                    i++
                }
                else -> {
                    result.add(DiffLine.Added(b[j]))
                    j++
                }
            }
        }
        while (i < n) { result.add(DiffLine.Removed(a[i])); i++ }
        while (j < m) { result.add(DiffLine.Added(b[j])); j++ }
        return result
    }

    private fun renderSpannable(context: Context, diff: List<DiffLine>): CharSequence {
        val builder = SpannableStringBuilder()
        diff.forEach { line ->
            val (prefix, text, colorRes) = when (line) {
                is DiffLine.Same -> Triple("  ", line.text, R.color.bh_text_dim)
                is DiffLine.Removed -> Triple("- ", line.text, R.color.bh_danger)
                is DiffLine.Added -> Triple("+ ", line.text, R.color.bh_success)
            }
            val start = builder.length
            builder.append(prefix).append(text).append("\n")
            builder.setSpan(
                ForegroundColorSpan(context.getColor(colorRes)),
                start, builder.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return builder
    }
}
