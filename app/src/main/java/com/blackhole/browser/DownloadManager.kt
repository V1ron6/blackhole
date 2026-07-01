package com.blackhole.browser

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages downloaded files in the blackhole/downloads directory.
 *
 * Files are stored locally and can be configured to auto-expire after a certain time
 * (default 24 hours, configurable in settings). All downloads are session-scoped -
 * when the app process ends, download metadata is cleared (but files persist on disk
 * until they expire or are manually deleted).
 */
class DownloadManager(private val context: Context, private val settings: Settings) {

    private val downloadsDir: File by lazy {
        File(context.getExternalFilesDir(null), "downloads").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Save a file to the downloads directory with an expiration timestamp.
     * @param fileName Name of the file to save
     * @param data File content bytes
     * @return The saved file or null if save failed
     */
    fun saveDownload(fileName: String, data: ByteArray): File? {
        return try {
            val file = File(downloadsDir, sanitizeFileName(fileName))
            file.writeBytes(data)
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get all downloaded files.
     */
    fun getDownloads(): List<File> {
        return downloadsDir.listFiles()?.toList() ?: emptyList()
    }

    /**
     * Delete a specific download file.
     */
    fun deleteDownload(file: File): Boolean {
        return file.exists() && file.delete()
    }

    /**
     * Clean up expired downloads based on retention setting.
     * Called periodically or on app startup.
     */
    fun cleanupExpiredDownloads() {
        val retentionMillis = TimeUnit.HOURS.toMillis(settings.downloadRetentionHours.toLong())
        val now = System.currentTimeMillis()

        downloadsDir.listFiles()?.forEach { file ->
            val ageMillis = now - file.lastModified()
            if (ageMillis > retentionMillis) {
                file.delete()
            }
        }
    }

    /**
     * Clear all downloads immediately (called on session reset).
     */
    fun clearAll() {
        downloadsDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Sanitize file name to prevent directory traversal.
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
    }

    companion object {
        const val MAX_FILE_SIZE = 100 * 1024 * 1024 // 100 MB
    }
}
