package com.blackhole.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
 * Captures the active tab's page as a PNG via View.draw(Canvas) - the
 * standard, always-available way to rasterize any Android View, WebView
 * included. Correction from an earlier version of this file: there is no
 * WebView.captureBitmapAsync in the public SDK (that call didn't exist -
 * a build error caught it). This captures only the currently visible
 * viewport, the same as taking a screenshot of what's on screen right now -
 * NOT the full scrollable page content beyond what's rendered. A true
 * full-page capture would mean scrolling and stitching multiple captures
 * together, which this doesn't do. Saved through DownloadManager into the
 * public Downloads/Blackhole folder.
 */
object ScreenshotCapture {

    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun show(context: Context, webView: WebView?, downloadManager: DownloadManager) {
        if (webView == null) {
            Toast.makeText(context, "No active tab to capture", Toast.LENGTH_SHORT).show()
            return
        }
        if (webView.width == 0 || webView.height == 0) {
            Toast.makeText(context, "Tab isn't visible/sized yet - try again after it loads", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogScreenshotBinding.inflate(LayoutInflater.from(context))
        val mainHandler = Handler(context.mainLooper)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Screenshot")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()

        val bitmap: Bitmap = try {
            Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888).also { bmp ->
                webView.draw(Canvas(bmp))
            }
        } catch (e: Exception) {
            binding.screenshotStatus.text = "Capture failed: ${e.message}"
            return
        }

        binding.screenshotPreview.setImageBitmap(bitmap)
        binding.screenshotStatus.text = "Captured visible area (${bitmap.width}\u00d7${bitmap.height}px)"
        binding.btnSaveScreenshot.isEnabled = true

        binding.btnSaveScreenshot.setOnClickListener {
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
