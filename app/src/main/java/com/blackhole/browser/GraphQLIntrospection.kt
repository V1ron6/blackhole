package com.blackhole.browser

import android.content.Context
import android.os.Handler
import android.view.LayoutInflater
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import com.blackhole.browser.databinding.DialogGraphqlIntrospectionBinding
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends the standard GraphQL introspection query to a given endpoint and
 * shows the raw schema JSON, pretty-printed. This just shows what the
 * server returns - it doesn't render a browsable schema tree, and it
 * doesn't try alternate endpoint paths if /graphql doesn't respond.
 */
object GraphQLIntrospection {

    private const val MAX_CHARS_SHOWN = 20_000

    // The standard, widely-used introspection query (a commonly published
    // subset covering types, fields, args, and enum values).
    private val introspectionQuery = """
        {
          "query": "query IntrospectionQuery { __schema { queryType { name } mutationType { name } subscriptionType { name } types { ...FullType } directives { name description locations args { ...InputValue } } } } fragment FullType on __Type { kind name description fields(includeDeprecated: true) { name description args { ...InputValue } type { ...TypeRef } isDeprecated deprecationReason } inputFields { ...InputValue } interfaces { ...TypeRef } enumValues(includeDeprecated: true) { name description isDeprecated deprecationReason } possibleTypes { ...TypeRef } } fragment InputValue on __InputValue { name description type { ...TypeRef } defaultValue } fragment TypeRef on __Type { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } } } }"
        }
    """.trimIndent()

    fun show(context: Context, webView: WebView?) {
        val binding = DialogGraphqlIntrospectionBinding.inflate(LayoutInflater.from(context))
        val defaultEndpoint = webView?.url?.let { current ->
            try {
                val u = URL(current)
                "${u.protocol}://${u.host}/graphql"
            } catch (e: Exception) {
                null
            }
        }
        binding.graphqlEndpoint.setText(defaultEndpoint ?: "")
        val mainHandler = Handler(context.mainLooper)

        binding.btnRunIntrospection.setOnClickListener {
            val endpoint = binding.graphqlEndpoint.text.toString().trim()
            if (endpoint.isBlank()) {
                binding.graphqlStatus.text = "Enter an endpoint URL first."
                return@setOnClickListener
            }

            binding.btnRunIntrospection.isEnabled = false
            binding.graphqlStatus.text = "Sending\u2026"
            binding.graphqlOutput.text = ""

            Thread {
                val result = try {
                    val connection = URL(endpoint).openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.doOutput = true
                    connection.outputStream.use { it.write(introspectionQuery.toByteArray(Charsets.UTF_8)) }

                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    connection.disconnect()

                    val pretty = try {
                        JSONObject(body).toString(2)
                    } catch (e: Exception) {
                        body // not valid JSON - show raw body as-is
                    }
                    "HTTP $code\n\n${pretty.take(MAX_CHARS_SHOWN)}" +
                        if (pretty.length > MAX_CHARS_SHOWN) "\n\n\u2026 (truncated)" else ""
                } catch (e: Exception) {
                    "Request failed: ${e.message}"
                }

                mainHandler.post {
                    binding.graphqlStatus.text = "Done"
                    binding.graphqlOutput.text = result
                    binding.btnRunIntrospection.isEnabled = true
                }
            }.start()
        }

        AlertDialog.Builder(context)
            .setTitle("GraphQL Introspection")
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .show()
    }
}
