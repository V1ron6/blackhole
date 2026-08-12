package com.blackhole.browser

import android.app.Activity

/**
 * Manages browser mode transitions and mode-specific UI/feature visibility.
 * Each mode has a specific set of visible features and UI elements.
 */
class ModeManager(private val activity: Activity, private val settings: Settings) {

    private var currentMode: BrowserMode = settings.browserMode
    private val modeListeners = mutableListOf<(BrowserMode) -> Unit>()

    fun getCurrentMode(): BrowserMode = currentMode

    fun setMode(mode: BrowserMode) {
        if (currentMode != mode) {
            currentMode = mode
            settings.browserMode = mode
            notifyModeChanged(mode)
        }
    }

    fun addModeListener(listener: (BrowserMode) -> Unit) {
        modeListeners.add(listener)
    }

    fun removeModeListener(listener: (BrowserMode) -> Unit) {
        modeListeners.remove(listener)
    }

    private fun notifyModeChanged(mode: BrowserMode) {
        modeListeners.forEach { it(mode) }
    }

    /**
     * Check if a feature is available in the current mode.
     */
    fun isFeatureAvailable(feature: ModeFeature): Boolean {
        return when (feature) {
            ModeFeature.POSTMAN_REQUESTS -> currentMode >= BrowserMode.INTERMEDIATE
            ModeFeature.JS_CONSOLE -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.INSPECTOR -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.JWT_DECODER -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.JSON_FORMATTER -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.REGEX_TESTER -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.STORAGE_INSPECTOR -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.USER_AGENT_SWITCHER -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.ROBOTS_FETCH -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.DIFF_VIEWER -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.REQUEST_TIMELINE -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.VIEWPORT_EMULATOR -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.ACCESSIBILITY_CHECKER -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.SCREENSHOT_CAPTURE -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.BOOKMARKLET_RUNNER -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.TECH_FINGERPRINT -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.WHOIS_LOOKUP -> currentMode >= BrowserMode.BYTEBANDIT
            ModeFeature.DNS_LOOKUP -> currentMode >= BrowserMode.BYTEBANDIT
            ModeFeature.FAVICON_HASH -> currentMode >= BrowserMode.BYTEBANDIT
            ModeFeature.PORT_SCANNER -> currentMode >= BrowserMode.BYTEBANDIT
            ModeFeature.GRAPHQL_INTROSPECTION -> currentMode >= BrowserMode.BYTEBANDIT
            ModeFeature.TOTP_GENERATOR -> currentMode >= BrowserMode.BYTEBANDIT
            ModeFeature.HAR_EXPORT -> currentMode >= BrowserMode.BYTEBANDIT
            ModeFeature.CASE_FILE_EXPORT -> currentMode >= BrowserMode.BYTEBANDIT
            ModeFeature.SECURITY_SCANNER -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.COOKIE_INSPECTOR -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.DATA_TOOLKIT -> currentMode >= BrowserMode.ADVANCE
            ModeFeature.SSH_TERMINAL -> currentMode >= BrowserMode.BYTEBANDIT
            ModeFeature.ADVANCED_SETTINGS -> currentMode >= BrowserMode.BYTEBANDIT
            ModeFeature.NOTIFICATIONS -> true // Available in all modes
            ModeFeature.DOWNLOADS -> true // Available in all modes
        }
    }

    /**
     * Reset mode to BASIC (called on session clear).
     */
    fun resetToBasicMode() {
        setMode(BrowserMode.BASIC)
    }

    /**
     * Re-read the mode from Settings and notify listeners if it changed.
     * SettingsActivity writes the mode directly to Settings (it doesn't hold
     * a ModeManager instance), so call this on resume to pick up the change.
     */
    fun syncFromSettings() {
        val stored = settings.browserMode
        if (stored != currentMode) {
            currentMode = stored
            notifyModeChanged(stored)
        }
    }

    enum class ModeFeature {
        POSTMAN_REQUESTS,
        JS_CONSOLE,
        INSPECTOR,
        JWT_DECODER,
        JSON_FORMATTER,
        REGEX_TESTER,
        STORAGE_INSPECTOR,
        USER_AGENT_SWITCHER,
        ROBOTS_FETCH,
        DIFF_VIEWER,
        REQUEST_TIMELINE,
        VIEWPORT_EMULATOR,
        ACCESSIBILITY_CHECKER,
        SCREENSHOT_CAPTURE,
        BOOKMARKLET_RUNNER,
        TECH_FINGERPRINT,
        WHOIS_LOOKUP,
        DNS_LOOKUP,
        FAVICON_HASH,
        PORT_SCANNER,
        GRAPHQL_INTROSPECTION,
        TOTP_GENERATOR,
        HAR_EXPORT,
        CASE_FILE_EXPORT,
        SECURITY_SCANNER,
        COOKIE_INSPECTOR,
        DATA_TOOLKIT,
        SSH_TERMINAL,
        ADVANCED_SETTINGS,
        NOTIFICATIONS,
        DOWNLOADS
    }
}
