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

    enum class ModeFeature {
        POSTMAN_REQUESTS,
        JS_CONSOLE,
        INSPECTOR,
        SSH_TERMINAL,
        ADVANCED_SETTINGS,
        NOTIFICATIONS,
        DOWNLOADS
    }
}
