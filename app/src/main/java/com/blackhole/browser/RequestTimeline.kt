package com.blackhole.browser

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogRequestTimelineBinding
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Chronological view of the active tab's request log.
 *
 * Worth being upfront about scope: RequestLogEntry only records host,
 * blocked/allowed, and a timestamp - no HTTP method, status code, or
 * response size, since the ad-blocker hook that produces these entries
 * runs at shouldInterceptRequest time, before any response exists. This is
 * a timing/host timeline, not a true status+size waterfall like a browser
 * DevTools Network tab. Getting real timing/status/size would mean
 * instrumenting the actual response, which is a bigger change.
 */
object RequestTimeline {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun show(context: Context, entries: List<RequestLogEntry>) {
        if (entries.isEmpty()) {
            Toast.makeText(context, "No requests logged for this tab yet", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogRequestTimelineBinding.inflate(LayoutInflater.from(context))
        // entries are stored newest-first (MainActivity inserts at index 0) -
        // reverse for a natural chronological reading order.
        val chronological = entries.reversed()
        val blockedCount = chronological.count { it.blocked }
        binding.timelineSummary.text =
            "${chronological.size} requests \u2022 $blockedCount blocked \u2022 oldest first"

        val density = context.resources.displayMetrics.density
        val paddingPx = (8 * density).toInt()
        var previousTimestamp: Long? = null

        chronological.forEach { entry ->
            val delta = previousTimestamp?.let { entry.timestampMillis - it }
            previousTimestamp = entry.timestampMillis
            val deltaText = delta?.let { " (+${it}ms)" } ?: ""

            val row = TextView(context).apply {
                textSize = 12f
                setPadding(0, paddingPx, 0, paddingPx)
                val statusBadge = if (entry.blocked) "BLOCKED" else "allowed"
                text = "${timeFormat.format(entry.timestampMillis)}$deltaText  [$statusBadge]\n${entry.host}"
                setTextColor(context.getColor(if (entry.blocked) R.color.bh_danger else R.color.bh_text))
            }
            binding.timelineResults.addView(row)
        }

        AlertDialog.Builder(context)
            .setTitle("Request Timeline")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }
}
