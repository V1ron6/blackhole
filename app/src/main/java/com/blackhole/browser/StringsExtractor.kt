package com.blackhole.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.provider.OpenableColumns
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogStringsExtractorBinding
import java.io.BufferedInputStream

/**
 * Pulls printable-ASCII runs out of an arbitrary file - the Unix `strings`
 * command's basic idea, for when you don't want to pull a CTF binary/image
 * into Termux just to grep it for a flag. Streams the file instead of
 * loading it whole into memory, and caps both input size and output string
 * count to stay reasonable on a phone.
 */
object StringsExtractor {

    private const val MAX_FILE_BYTES = 20L * 1024 * 1024
    private const val MAX_STRINGS = 3000
    private const val READ_BUFFER = 8192

    fun show(context: Context, pickFile: ((Uri) -> Unit) -> Unit) {
        val binding = DialogStringsExtractorBinding.inflate(LayoutInflater.from(context))
        val mainHandler = Handler(context.mainLooper)
        var selectedUri: Uri? = null
        var selectedName: String = ""

        binding.btnChooseFileStrings.setOnClickListener {
            pickFile { uri ->
                val (name, size) = queryNameAndSize(context, uri)
                if (size in 0..MAX_FILE_BYTES || size < 0) {
                    selectedUri = uri
                    selectedName = name
                    binding.btnExtractStrings.isEnabled = true
                    binding.stringsStatus.text = "$name selected (${formatSize(size)}). Tap Extract."
                } else {
                    selectedUri = null
                    binding.btnExtractStrings.isEnabled = false
                    binding.stringsStatus.text = "$name is ${formatSize(size)} - too large (max 20 MB)."
                }
                binding.stringsResults.text = ""
            }
        }

        binding.btnExtractStrings.setOnClickListener {
            val uri = selectedUri ?: return@setOnClickListener
            val minLen = binding.stringsMinLength.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 4
            val filterText = binding.stringsFilter.text.toString().trim()
            val filterRegex = if (filterText.isEmpty()) null else try {
                Regex(filterText)
            } catch (e: Exception) {
                binding.stringsStatus.text = "Invalid filter regex: ${e.message}"
                return@setOnClickListener
            }

            binding.btnExtractStrings.isEnabled = false
            binding.stringsStatus.text = "Extracting\u2026"
            binding.stringsResults.text = ""

            Thread {
                val result = extractStrings(context, uri, minLen, filterRegex)
                mainHandler.post {
                    binding.btnExtractStrings.isEnabled = true
                    binding.stringsStatus.text = "$selectedName - ${result.second} string(s)${if (result.third) " (capped at $MAX_STRINGS)" else ""}"
                    binding.stringsResults.text = result.first
                }
            }.start()
        }

        AlertDialog.Builder(context)
            .setTitle("Strings Extractor")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }

    /** Returns (joined output text, count found, wasCapped). */
    private fun extractStrings(context: Context, uri: Uri, minLen: Int, filter: Regex?): Triple<String, Int, Boolean> {
        val found = mutableListOf<String>()
        var capped = false
        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                BufferedInputStream(raw, READ_BUFFER).use { stream ->
                    val current = StringBuilder()
                    val buffer = ByteArray(READ_BUFFER)
                    var read: Int
                    fun flush() {
                        if (current.length >= minLen) {
                            val s = current.toString()
                            if (filter == null || filter.containsMatchIn(s)) {
                                found.add(s)
                                if (found.size >= MAX_STRINGS) capped = true
                            }
                        }
                        current.clear()
                    }
                    while (!capped && stream.read(buffer).also { read = it } != -1) {
                        for (i in 0 until read) {
                            val b = buffer[i].toInt() and 0xFF
                            if (b in 32..126) {
                                current.append(b.toChar())
                            } else {
                                flush()
                                if (capped) break
                            }
                        }
                    }
                    if (!capped) flush()
                }
            }
        } catch (e: Exception) {
            return Triple("Error reading file: ${e.message}", 0, false)
        }
        return Triple(found.joinToString("\n"), found.size, capped)
    }

    private fun queryNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "unknown"
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Exception) { }
        return name to size
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "unknown size"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
