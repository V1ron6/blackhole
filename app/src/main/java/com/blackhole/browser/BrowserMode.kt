package com.blackhole.browser

/**
 * Browser operation modes, each with dedicated interfaces and capabilities.
 *
 * BASIC       - Standard browser with 2 tabs, navigation, JS toggle, ad blocking.
 *               Resets to this mode on session clear.
 *
 * INTERMEDIATE - BASIC + Postman-like HTTP method selector (GET, POST, PUT, PATCH, DELETE),
 *               request headers/body editing, integrated request tester.
 *
 * ADVANCE      - INTERMEDIATE + JavaScript console (execute arbitrary JS in page context),
 *               inspector tools, expanded network logging, debugging utilities.
 *
 * BYTEBANDIT   - ADVANCE + advanced settings for cybersecurity professionals,
 *               SSH terminal integration, advanced proxy configurations, pentesting tools.
 */
enum class BrowserMode(val displayName: String, val description: String) {
    BASIC("Basic", "Standard browser"),
    INTERMEDIATE("Intermediate", "Postman-like requests"),
    ADVANCE("Advance", "Developer tools & console"),
    BYTEBANDIT("ByteBandit", "Advanced cybersecurity tools");

    companion object {
        fun fromString(value: String?): BrowserMode =
            values().find { it.name == value } ?: BASIC
    }
}
