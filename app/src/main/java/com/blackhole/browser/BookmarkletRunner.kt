package com.blackhole.browser

import android.content.Context
import android.view.LayoutInflater
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogBookmarkletRunnerBinding
import org.json.JSONArray
import org.json.JSONObject

/**
 * Save and re-run small JavaScript snippets against the active tab, one tap
 * at a time - like a personal bookmarklet library. Snippets persist across
 * app restarts (stored locally, never sent anywhere); running one goes
 * through the same WebView.evaluateJavascript path as the JS console.
 */
object BookmarkletRunner {

    private const val PREFS_NAME = "blackhole_bookmarklets"
    private const val KEY_SNIPPETS = "snippets"

    private data class Snippet(val name: String, val code: String)

    private fun loadSnippets(context: Context): MutableList<Snippet> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SNIPPETS, null) ?: return mutableListOf()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            Snippet(obj.getString("name"), obj.getString("code"))
        }.toMutableList()
    }

    private fun saveSnippets(context: Context, snippets: List<Snippet>) {
        val array = JSONArray()
        snippets.forEach { snippet ->
            array.put(JSONObject().apply {
                put("name", snippet.name)
                put("code", snippet.code)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNIPPETS, array.toString())
            .apply()
    }

    fun show(context: Context, webView: WebView?) {
        val binding = DialogBookmarkletRunnerBinding.inflate(LayoutInflater.from(context))
        val snippets = loadSnippets(context)

        fun renderList() {
            binding.bookmarkletList.removeAllViews()
            if (snippets.isEmpty()) {
                binding.bookmarkletList.addView(TextView(context).apply {
                    text = "No saved snippets yet."
                    textSize = 12f
                    setTextColor(context.getColor(R.color.bh_text_dim))
                })
                return
            }
            snippets.forEach { snippet ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 8)
                }
                val label = TextView(context).apply {
                    text = snippet.name
                    textSize = 13f
                    setTextColor(context.getColor(R.color.bh_text))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val runButton = Button(context).apply {
                    text = "Run"
                    isAllCaps = false
                    setOnClickListener {
                        if (webView == null) {
                            Toast.makeText(context, "No active tab", Toast.LENGTH_SHORT).show()
                        } else {
                            webView.evaluateJavascript(snippet.code, null)
                            Toast.makeText(context, "Ran \"${snippet.name}\"", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                val deleteButton = Button(context).apply {
                    text = "Delete"
                    isAllCaps = false
                    setOnClickListener {
                        snippets.remove(snippet)
                        saveSnippets(context, snippets)
                        renderList()
                    }
                }
                row.addView(label)
                row.addView(runButton)
                row.addView(deleteButton)
                binding.bookmarkletList.addView(row)
            }
        }

        binding.btnAddBookmarklet.setOnClickListener {
            val name = binding.bookmarkletName.text.toString().trim()
            val code = binding.bookmarkletCode.text.toString().trim()
            if (name.isBlank() || code.isBlank()) {
                Toast.makeText(context, "Enter both a name and some code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            snippets.add(Snippet(name, code))
            saveSnippets(context, snippets)
            binding.bookmarkletName.setText("")
            binding.bookmarkletCode.setText("")
            renderList()
        }

        renderList()

        AlertDialog.Builder(context)
            .setTitle("Bookmarklets")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }
}
