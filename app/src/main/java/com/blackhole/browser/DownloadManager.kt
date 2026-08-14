package com.blackhole.browser

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Manages downloaded files, saving them into the device's public Downloads
 * folder (Downloads/Blackhole) via MediaStore, so they show up in the
 * system's own Files/Downloads app - not just inside this app's private
 * storage. This requires no runtime storage permission on API 29+ (this
 * app's minSdk), since writing your own MediaStore rows doesn't need
 * WRITE_EXTERNAL_STORAGE under scoped storage.
 *
 * Files can be configured to auto-expire after a certain time (default 24
 * hours, configurable in Settings). Expiry and "clear all" only ever act on
 * rows this app itself inserted (scoped to Download/Blackhole) - they never
 * touch files from other apps or the user's own Downloads.
 */
class DownloadManager(private val context: Context, private val settings: Settings) {

    data class DownloadEntry(val uri: Uri, val displayName: String, val dateAddedMillis: Long)

    private val relativeSubPath = "${Environment.DIRECTORY_DOWNLOADS}/Blackhole"
    private val resolver get() = context.contentResolver
    private val collection: Uri get() = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    /**
     * Save a file into the device's public Downloads/Blackhole folder.
     * @return the content Uri of the saved file, or null if the save failed
     */
    fun saveDownload(fileName: String, data: ByteArray, mimeType: String? = null): Uri? {
        val safeName = sanitizeFileName(fileName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeSubPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(data) }
                ?: throw IOException("Could not open output stream for $uri")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * List every file this app has saved to Downloads/Blackhole, most recent first.
     */
    fun getDownloads(): List<DownloadEntry> {
        val entries = mutableListOf<DownloadEntry>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf("$relativeSubPath/")

        resolver.query(
            collection, projection, selection, args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                // DATE_ADDED is stored in seconds, not millis.
                entries.add(DownloadEntry(uri, cursor.getString(nameCol), cursor.getLong(dateCol) * 1000))
            }
        }
        return entries
    }

    /**
     * Delete a specific download.
     */
    fun deleteDownload(uri: Uri): Boolean {
        return try {
            resolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Clean up expired downloads based on the retention setting.
     * Called periodically or on app startup.
     */
    fun cleanupExpiredDownloads() {
        val retentionMillis = TimeUnit.HOURS.toMillis(settings.downloadRetentionHours.toLong())
        val now = System.currentTimeMillis()
        getDownloads().forEach { entry ->
            if (now - entry.dateAddedMillis > retentionMillis) {
                deleteDownload(entry.uri)
            }
        }
    }

    /**
     * Clear all downloads immediately (called on session reset).
     */
    fun clearAll() {
        getDownloads().forEach { deleteDownload(it.uri) }
    }

    /**
     * Sanitize file name to prevent directory traversal / invalid MediaStore names.
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "download" }
    }

    companion object {
        const val MAX_FILE_SIZE = 100 * 1024 * 1024 // 100 MB
    }
}
