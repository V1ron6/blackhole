package com.blackhole.browser

/**
 * HTTP request methods supported in Intermediate mode and above.
 */
enum class HttpMethod(val displayName: String) {
    GET("GET"),
    POST("POST"),
    PUT("PUT"),
    PATCH("PATCH"),
    DELETE("DELETE");

    companion object {
        fun fromString(value: String?): HttpMethod =
            values().find { it.name == value } ?: GET
    }
}
