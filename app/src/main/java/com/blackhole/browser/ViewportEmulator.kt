package com.blackhole.browser

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogViewportEmulatorBinding

/**
 * Resizes the active tab's WebView within its container to preview common
 * responsive breakpoints. This is a size-only preview - it doesn't change
 * device pixel ratio, touch vs. mouse input simulation, or the UA string
 * (pair it with the User-Agent Switcher for that). Good for "does this
 * layout break at 375px" checks, not a full responsive design mode.
 */
object ViewportEmulator {

    private data class Size(val widthDp: Int, val heightDp: Int)

    fun show(context: Context, webView: WebView?) {
        if (webView == null) {
            Toast.makeText(context, "No active tab", Toast.LENGTH_SHORT).show()
            return
        }

        val binding = DialogViewportEmulatorBinding.inflate(LayoutInflater.from(context))
        val density = context.resources.displayMetrics.density

        fun applySize(size: Size?) {
            val params = webView.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            if (size == null) {
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
            } else {
                params.width = (size.widthDp * density).toInt()
                params.height = (size.heightDp * density).toInt()
                params.gravity = Gravity.TOP or Gravity.START
            }
            webView.layoutParams = params
            webView.requestLayout()
        }

        binding.btnViewportMobile.setOnClickListener { applySize(Size(375, 667)) }
        binding.btnViewportMobileL.setOnClickListener { applySize(Size(414, 896)) }
        binding.btnViewportTablet.setOnClickListener { applySize(Size(768, 1024)) }
        binding.btnViewportDesktop.setOnClickListener { applySize(Size(1366, 768)) }
        binding.btnViewportReset.setOnClickListener {
            applySize(null)
            Toast.makeText(context, "Reset to full size", Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(context)
            .setTitle("Viewport Emulator")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }
}
