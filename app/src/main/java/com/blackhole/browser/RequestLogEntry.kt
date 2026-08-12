package com.blackhole.browser

/**
 * One network request observed by a tab's WebViewClient.shouldInterceptRequest.
 * url and method come directly from WebResourceRequest - real observed values,
 * not inferred. There's no status code or response size here: shouldInterceptRequest
 * fires before any response exists, so those genuinely aren't available at this
 * point without a bigger change to actually perform/observe the fetch ourselves.
 */
data class RequestLogEntry(
    val host: String,
    val url: String,
    val method: String,
    val blocked: Boolean,
    val timestampMillis: Long
)
