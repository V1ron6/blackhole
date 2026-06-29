package com.blackhole.browser

/** One network request observed by a tab's WebViewClient. */
data class RequestLogEntry(
    val host: String,
    val blocked: Boolean,
    val timestampMillis: Long
)
