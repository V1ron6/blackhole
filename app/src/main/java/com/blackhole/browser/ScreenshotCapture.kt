package com.blackhole.browser

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogScreenshotBinding
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures the active tab's page as a PNG via WebView.captureBitmapAsync
 * (API 28+, this app's minSdk is 29 so it's always available). Per the
 * platform API, this captures the WebView's full composited content, not
 * just the visible viewport - unlike a plain View drawing cache. Saved
 * through DownloadManager into the public Downloads/Blackhole folder.
 */
object ScreenshotCapture {

    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun show(context: Context, webView: WebView?, downloadManager: DownloadManager) {
        if (webView == null) {
            Toast.makeText(context, "No active tab to capture", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogScreenshotBinding.inflate(LayoutInflater.from(context))
        var capturedBitmap: Bitmap? = null
        val mainHandler = Handler(context.mainLooper)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Screenshot")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()

        try {
            webView.captureBitmapAsync { bitmap ->
                mainHandler.post {
                    capturedBitmap = bitmap
                    binding.screenshotPreview.setImageBitmap(bitmap)
                    binding.screenshotStatus.text = "Captured (${bitmap.width}\u00d7${bitmap.height}px)"
                    binding.btnSaveScreenshot.isEnabled = true
                }
            }
        } catch (e: Exception) {
            binding.screenshotStatus.text = "Capture failed: ${e.message}"
            return
        }

        binding.btnSaveScreenshot.setOnClickListener {
            val bitmap = capturedBitmap ?: return@setOnClickListener
            binding.btnSaveScreenshot.isEnabled = false
            binding.screenshotStatus.text = "Saving\u2026"

            Thread {
                val fileName = "screenshot-${fileTimeFormat.format(Date())}.png"
                val bytes = ByteArrayOutputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.toByteArray()
                }
                val saved = downloadManager.saveDownload(fileName, bytes, "image/png")
                mainHandler.post {
                    if (saved != null) {
                        binding.screenshotStatus.text = "Saved $fileName to Downloads/Blackhole"
                    } else {
                        binding.screenshotStatus.text = "Failed to save"
                        binding.btnSaveScreenshot.isEnabled = true
                    }
                }
            }.start()
        }
    }
}
