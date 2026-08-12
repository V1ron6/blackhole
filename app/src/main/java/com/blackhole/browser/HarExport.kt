package com.blackhole.browser

import android.content.Context
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Exports a tab's request log as a HAR 1.2 file. Being upfront about what's
 * real here: url, method, and timestamp are actual observed values from
 * WebResourceRequest. Status code and response size are NOT available -
 * shouldInterceptRequest (where this log is built) fires before any
 * response exists, so there's nothing real to put there. Rather than
 * fabricate a status code, every entry's response.status is 0 with a
 * comment explaining why, and blocked/allowed is recorded in the entry's
 * own comment field. A HAR viewer expecting real status codes will show
 * these as failed/unknown requests - that's accurate to what this app
 * actually observed, not a bug in the export.
 */
object HarExport {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun export(context: Context, entries: List<RequestLogEntry>, downloadManager: DownloadManager) {
        if (entries.isEmpty()) {
            Toast.makeText(context, "No requests logged for this tab yet", Toast.LENGTH_SHORT).show()
            return
        }

        val har = buildHar(entries)
        val fileName = "requestlog-${fileTimeFormat.format(Date())}.har"

        Thread {
            val saved = downloadManager.saveDownload(fileName, har.toString(2).toByteArray(Charsets.UTF_8), "application/json")
            android.os.Handler(context.mainLooper).post {
                if (saved != null) {
                    Toast.makeText(context, "Saved $fileName to Downloads/Blackhole", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to save $fileName", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun buildHar(entries: List<RequestLogEntry>): JSONObject {
        val chronological = entries.reversed()
        val har = JSONObject()
        val log = JSONObject()
        log.put("version", "1.2")
        log.put("creator", JSONObject().apply {
            put("name", "Blackhole")
            put("version", "1.0")
        })
        log.put("comment", "Exported from Blackhole's request log. status/response fields are placeholders " +
            "(0 / empty) because this log is built at request-interception time, before any response exists - " +
            "see each entry's comment for blocked/allowed instead.")

        val entriesArray = JSONArray()
        chronological.forEach { entry ->
            val harEntry = JSONObject()
            harEntry.put("startedDateTime", isoFormat.format(Date(entry.timestampMillis)))
            harEntry.put("time", 0)
            harEntry.put("comment", if (entry.blocked) "blocked by Blackhole" else "allowed")

            val request = JSONObject()
            request.put("method", entry.method)
            request.put("url", entry.url)
            request.put("httpVersion", "unknown")
            request.put("headers", JSONArray())
            request.put("queryString", JSONArray())
            request.put("cookies", JSONArray())
            request.put("headersSize", -1)
            request.put("bodySize", -1)
            harEntry.put("request", request)

            val response = JSONObject()
            response.put("status", 0)
            response.put("statusText", "not observed")
            response.put("httpVersion", "unknown")
            response.put("headers", JSONArray())
            response.put("cookies", JSONArray())
            response.put("content", JSONObject().apply {
                put("size", 0)
                put("mimeType", "")
            })
            response.put("redirectURL", "")
            response.put("headersSize", -1)
            response.put("bodySize", -1)
            harEntry.put("response", response)

            harEntry.put("cache", JSONObject())
            harEntry.put("timings", JSONObject().apply {
                put("send", 0)
                put("wait", 0)
                put("receive", 0)
            })

            entriesArray.put(harEntry)
        }
        log.put("entries", entriesArray)
        har.put("log", log)
        return har
    }
}
