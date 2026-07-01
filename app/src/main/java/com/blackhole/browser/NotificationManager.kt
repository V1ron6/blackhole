package com.blackhole.browser

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Manages notifications and alerts across the application.
 * Provides organized, non-intrusive toast and dialog notifications.
 */
object NotificationManager {

    fun showToast(activity: Activity, message: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(activity, message, duration).show()
    }

    fun showAlert(activity: Activity, title: String, message: String, onDismiss: (() -> Unit)? = null) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setCancelable(true)
            .create()
            .show()
    }

    fun showConfirm(
        activity: Activity,
        title: String,
        message: String,
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Confirm") { dialog, _ ->
                dialog.dismiss()
                onConfirm()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                onCancel?.invoke()
            }
            .setCancelable(true)
            .create()
            .show()
    }
}
