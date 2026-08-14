package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bundles what's available for the current tab's session into a single zip
 * for a writeup or handoff: the request log (as HAR, reusing HarExport's
 * builder) and a plain-text manifest describing what's included.
 *
 * Scope note: this does NOT automatically include Postman request history
 * or any screenshots you've taken - those live in their own dialogs and
 * aren't currently tracked in a shared per-session store, so there's
 * nothing to pull in for them yet. If you've saved a screenshot via the
 * Screenshot tool, it's already sitting in Downloads/Blackhole alongside
 * this zip - just not folded into it automatically.
 */
object CaseFileExport {

    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val displayTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun export(context: Context, entries: List<RequestLogEntry>, downloadManager: DownloadManager) {
        val mainHandler = Handler(context.mainLooper)
        val timestamp = fileTimeFormat.format(Date())
        val fileName = "casefile-$timestamp.zip"

        Thread {
            try {
                val zipBytes = ByteArrayOutputStream().use { byteStream ->
                    ZipOutputStream(byteStream).use { zip ->
                        zip.putNextEntry(ZipEntry("manifest.txt"))
                        zip.write(buildManifest(entries, timestamp).toByteArray(Charsets.UTF_8))
                        zip.closeEntry()

                        if (entries.isNotEmpty()) {
                            zip.putNextEntry(ZipEntry("requestlog.har"))
                            zip.write(buildHarJson(entries).toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                        }
                    }
                    byteStream.toByteArray()
                }

                val saved = downloadManager.saveDownload(fileName, zipBytes, "application/zip")
                mainHandler.post {
                    if (saved != null) {
                        Toast.makeText(context, "Saved $fileName to Downloads/Blackhole", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to save $fileName", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun buildManifest(entries: List<RequestLogEntry>, timestamp: String): String {
        return buildString {
            append("Blackhole case file\n")
            append("Generated: ${displayTimeFormat.format(Date())}\n")
            append("Export id: $timestamp\n\n")
            append("Contents:\n")
            if (entries.isNotEmpty()) {
                append("  - requestlog.har (${entries.size} requests, this tab only)\n")
            } else {
                append("  - (no requests logged for this tab - requestlog.har omitted)\n")
            }
            append("\nNot included automatically:\n")
            append("  - Postman request history (not persisted anywhere yet)\n")
            append("  - Screenshots (saved separately via the Screenshot tool, if used)\n")
            append("\nSee requestlog.har's own \"comment\" fields for what status/response data is\n")
            append("and isn't real - it's built from request interception, before any response exists.\n")
        }
    }

    /**
     * Same construction as HarExport.buildHar, kept local to avoid making
     * that object's internals public just for this one caller.
     */
    private fun buildHarJson(entries: List<RequestLogEntry>): String {
        val chronological = entries.reversed()
        val log = JSONObject()
        log.put("version", "1.2")
        log.put("creator", JSONObject().apply {
            put("name", "Blackhole")
            put("version", "1.0")
        })
        log.put("comment", "status/response fields are placeholders - see HarExport.kt's doc comment for why.")

        val entriesArray = JSONArray()
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        chronological.forEach { entry ->
            val harEntry = JSONObject()
            harEntry.put("startedDateTime", isoFormat.format(Date(entry.timestampMillis)))
            harEntry.put("time", 0)
            harEntry.put("comment", if (entry.blocked) "blocked by Blackhole" else "allowed")
            harEntry.put("request", JSONObject().apply {
                put("method", entry.method)
                put("url", entry.url)
                put("httpVersion", "unknown")
                put("headers", JSONArray())
                put("queryString", JSONArray())
                put("cookies", JSONArray())
                put("headersSize", -1)
                put("bodySize", -1)
            })
            harEntry.put("response", JSONObject().apply {
                put("status", 0)
                put("statusText", "not observed")
                put("httpVersion", "unknown")
                put("headers", JSONArray())
                put("cookies", JSONArray())
                put("content", JSONObject().apply { put("size", 0); put("mimeType", "") })
                put("redirectURL", "")
                put("headersSize", -1)
                put("bodySize", -1)
            })
            harEntry.put("cache", JSONObject())
            harEntry.put("timings", JSONObject().apply {
                put("send", 0); put("wait", 0); put("receive", 0)
            })
            entriesArray.put(harEntry)
        }
        log.put("entries", entriesArray)
        return JSONObject().put("log", log).toString(2)
    }
}
