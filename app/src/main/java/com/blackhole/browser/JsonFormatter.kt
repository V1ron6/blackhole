package com.blackhole.browser

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogJsonFormatterBinding
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Validates and pretty-prints JSON, plus a lightweight path query
 * (dot-notation with bracket array indices, e.g. "data.items[0].name").
 * This is a small hand-rolled subset of JSONPath, not a full implementation
 * - no wildcards, no filter expressions, no recursive descent. It covers
 * the common "drill into a specific field" case; anything fancier needs a
 * real JSONPath library.
 */
object JsonFormatter {

    fun show(context: Context) {
        val binding = DialogJsonFormatterBinding.inflate(LayoutInflater.from(context))
        var parsedRoot: Any? = null

        binding.btnFormatJson.setOnClickListener {
            val input = binding.jsonInput.text.toString().trim()
            try {
                val root = JSONTokener(input).nextValue()
                parsedRoot = root
                val pretty = when (root) {
                    is JSONObject -> root.toString(2)
                    is JSONArray -> root.toString(2)
                    else -> root.toString()
                }
                binding.jsonOutput.text = pretty
                binding.jsonStatus.text = "\u2713 Valid JSON"
                binding.jsonStatus.setTextColor(context.getColor(R.color.bh_success))
            } catch (e: JSONException) {
                parsedRoot = null
                binding.jsonOutput.text = ""
                binding.jsonStatus.text = "\u2717 Invalid JSON: ${e.message}"
                binding.jsonStatus.setTextColor(context.getColor(R.color.bh_danger))
            }
        }

        binding.btnRunPath.setOnClickListener {
            val root = parsedRoot
            if (root == null) {
                binding.jsonPathOutput.text = "Validate the JSON first."
                return@setOnClickListener
            }
            val path = binding.jsonPathInput.text.toString().trim()
            binding.jsonPathOutput.text = try {
                val result = queryPath(root, path)
                stringifyResult(result)
            } catch (e: Exception) {
                "Path error: ${e.message}"
            }
        }

        AlertDialog.Builder(context)
            .setTitle("JSON Formatter")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun stringifyResult(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.toString(2)
            is JSONArray -> value.toString(2)
            else -> value.toString()
        }
    }

    private fun queryPath(root: Any, path: String): Any? {
        if (path.isBlank()) return root
        val cleaned = path.removePrefix("$.").removePrefix("$")
        var current: Any? = root

        cleaned.split(".").forEach { rawSegment ->
            if (rawSegment.isBlank()) return@forEach
            val keyMatch = Regex("^([a-zA-Z0-9_]*)((\\[\\d+])*)$").find(rawSegment)
                ?: throw IllegalArgumentException("Can't parse segment '$rawSegment'")
            val key = keyMatch.groupValues[1]
            val indexPart = keyMatch.groupValues[2]

            if (key.isNotEmpty()) {
                val obj = current as? JSONObject
                    ?: throw IllegalArgumentException("Expected an object to read key '$key'")
                if (!obj.has(key)) throw IllegalArgumentException("No key '$key' at this level")
                current = obj.get(key)
            }

            Regex("\\[(\\d+)]").findAll(indexPart).forEach { indexMatch ->
                val index = indexMatch.groupValues[1].toInt()
                val arr = current as? JSONArray
                    ?: throw IllegalArgumentException("Expected an array for index [$index]")
                if (index !in 0 until arr.length()) throw IllegalArgumentException("Index $index out of bounds (length ${arr.length()})")
                current = arr.get(index)
            }
        }
        return current
    }
}
