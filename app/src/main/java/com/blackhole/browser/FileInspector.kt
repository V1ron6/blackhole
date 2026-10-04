package com.blackhole.browser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogFileInspectorBinding
import java.util.zip.ZipInputStream

/**
 * Identifies a file's real type from its magic bytes, independent of
 * whatever extension it was given - the classic CTF "rename the flag to
 * .png" trick. Only reads the first few KB (magic bytes live at fixed small
 * offsets; no need to pull a whole file into memory for that), except for
 * the ZIP entry listing and ELF section walk, which need more of the file
 * but still stream it rather than loading it all into a ByteArray at once.
 */
object FileInspector {

    private const val HEADER_READ_BYTES = 64
    private const val MAX_ZIP_ENTRIES_SHOWN = 50

    private data class Signature(val category: String, val magic: ByteArray, val typicalExtensions: Set<String>)

    private val signatures = listOf(
        Signature("ELF executable", byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()), setOf("elf", "", "bin", "out", "so")),
        Signature("Windows PE executable", byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()), setOf("exe", "dll", "sys")),
        Signature("PNG image", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A), setOf("png")),
        Signature("JPEG image", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), setOf("jpg", "jpeg")),
        Signature("GIF image", byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte()), setOf("gif")),
        Signature("BMP image", byteArrayOf('B'.code.toByte(), 'M'.code.toByte()), setOf("bmp")),
        Signature("PDF document", byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte()), setOf("pdf")),
        Signature("GZIP archive", byteArrayOf(0x1F, 0x8B.toByte()), setOf("gz", "tgz")),
        Signature("RAR archive", byteArrayOf('R'.code.toByte(), 'a'.code.toByte(), 'r'.code.toByte(), '!'.code.toByte()), setOf("rar")),
        Signature("7-Zip archive", byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C), setOf("7z")),
        Signature("SQLite database", "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII), setOf("db", "sqlite", "sqlite3")),
        Signature("Old MS Office (OLE compound file)", byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()), setOf("doc", "xls", "ppt")),
        // ZIP-family (also apk/jar/docx/xlsx/pptx/odt) - checked after more
        // specific OLE/etc. signatures since several formats share this magic.
        Signature("ZIP-based archive", byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04), setOf("zip", "apk", "jar", "docx", "xlsx", "pptx", "odt"))
    )

    fun show(context: Context, pickFile: ((Uri) -> Unit) -> Unit) {
        val binding = DialogFileInspectorBinding.inflate(LayoutInflater.from(context))

        binding.btnChooseFile.setOnClickListener {
            pickFile { uri -> inspectFile(context, uri, binding) }
        }

        AlertDialog.Builder(context)
            .setTitle("File Inspector")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }

    /** Content-URI metadata (display name, size) via the standard OpenableColumns projection. */
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

    private fun inspectFile(context: Context, uri: Uri, binding: DialogFileInspectorBinding) {
        val (name, size) = queryNameAndSize(context, uri)
        val claimedExt = name.substringAfterLast('.', "").lowercase()

        val header = try {
            context.contentResolver.openInputStream(uri)?.use { it.readNBytes(HEADER_READ_BYTES) }
        } catch (e: Exception) {
            null
        }

        if (header == null) {
            binding.fileInspectorSummary.text = "$name - could not read file."
            binding.fileInspectorDetail.text = ""
            return
        }

        val match = signatures.firstOrNull { sig -> header.size >= sig.magic.size && header.copyOfRange(0, sig.magic.size).contentEquals(sig.magic) }

        val sizeText = if (size >= 0) formatSize(size) else "unknown size"
        binding.fileInspectorSummary.text = "$name  \u2022  $sizeText"

        val detail = StringBuilder()
        if (match == null) {
            detail.append("Type: unrecognized (no known magic bytes matched)\n")
            detail.append("First bytes (hex): ${header.take(16).joinToString(" ") { "%02x".format(it) }}\n")
        } else {
            detail.append("Detected type: ${match.category}\n")
            val mismatched = claimedExt.isNotEmpty() && claimedExt !in match.typicalExtensions
            if (mismatched) {
                detail.append("\u26A0 MISMATCH: file extension is \".$claimedExt\" but content looks like ${match.category}. Classic disguised-file trick - worth a closer look.\n")
            } else if (claimedExt.isEmpty()) {
                detail.append("(No file extension to compare against.)\n")
            } else {
                detail.append("Extension \".$claimedExt\" is consistent with this type.\n")
            }

            when (match.category) {
                "ELF executable" -> detail.append("\n").append(parseElfHeader(context, uri))
                "ZIP-based archive" -> detail.append("\n").append(listZipEntries(context, uri))
            }
        }

        binding.fileInspectorDetail.text = detail.toString()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    /** Reads the fixed-offset fields of an ELF header - class, endianness, type, machine. */
    private fun parseElfHeader(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val e = stream.readNBytes(20)
                if (e.size < 20) return "ELF header truncated."
                val is64 = e[4].toInt() == 2
                val littleEndian = e[5].toInt() == 1
                fun u16(offset: Int): Int {
                    val lo = e[offset].toInt() and 0xFF
                    val hi = e[offset + 1].toInt() and 0xFF
                    return if (littleEndian) (hi shl 8) or lo else (lo shl 8) or hi
                }
                val type = when (u16(16)) {
                    1 -> "REL (relocatable)"
                    2 -> "EXEC (executable)"
                    3 -> "DYN (shared object / PIE)"
                    4 -> "CORE (core dump)"
                    else -> "unknown (${u16(16)})"
                }
                val machine = when (u16(18)) {
                    0x03 -> "x86"
                    0x3E -> "x86-64"
                    0x28 -> "ARM"
                    0xB7 -> "AArch64"
                    0x08 -> "MIPS"
                    else -> "unknown (0x${"%x".format(u16(18))})"
                }
                "Class: ${if (is64) "64-bit" else "32-bit"}\n" +
                    "Endianness: ${if (littleEndian) "little" else "big"}\n" +
                    "Type: $type\n" +
                    "Machine: $machine"
            } ?: "Could not read ELF header."
        } catch (e: Exception) {
            "Could not parse ELF header: ${e.message}"
        }
    }

    /** Lists entry names in a ZIP-family container - apk/jar/docx/etc. included. */
    private fun listZipEntries(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    val names = mutableListOf<String>()
                    var entry = zip.nextEntry
                    while (entry != null && names.size < MAX_ZIP_ENTRIES_SHOWN) {
                        names.add(entry.name)
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    val isApk = names.any { it == "AndroidManifest.xml" } && names.any { it == "classes.dex" }
                    val header = if (isApk) "Looks like an APK (has AndroidManifest.xml + classes.dex).\n\n" else ""
                    header + "Contents (first ${names.size}):\n" + names.joinToString("\n") { "  $it" }
                }
            } ?: "Could not open as ZIP."
        } catch (e: Exception) {
            "Could not list ZIP entries: ${e.message}"
        }
    }
}
