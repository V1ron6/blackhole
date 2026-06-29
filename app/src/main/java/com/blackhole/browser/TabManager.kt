package com.blackhole.browser

import android.webkit.WebView

/**
 * A single browser tab: its WebView instance + a stable id.
 *
 * jsEnabled and requestLog are mutable per-tab state layered on top of the
 * original tab model - existing code that only cares about id/webView is
 * unaffected.
 */
data class Tab(
    val id: Int,
    val webView: WebView,
    var jsEnabled: Boolean = false,
    val requestLog: MutableList<RequestLogEntry> = mutableListOf()
)

/**
 * Manages the tab set. Blackhole intentionally caps the user at 2 concurrent
 * tabs - this is a deliberate design constraint (reduces memory/attack surface
 * and keeps the UI minimal), not a placeholder limit to raise later.
 */
class TabManager {

    companion object {
        const val MAX_TABS = 2
    }

    private val tabs = mutableListOf<Tab>()
    private var nextId = 0
    var activeIndex: Int = -1
        private set

    val size: Int get() = tabs.size
    val isAtCapacity: Boolean get() = tabs.size >= MAX_TABS

    fun activeTab(): Tab? = tabs.getOrNull(activeIndex)
    fun allTabs(): List<Tab> = tabs.toList()

    /** Returns the new tab, or null if already at MAX_TABS. */
    fun addTab(webView: WebView, requestLog: MutableList<RequestLogEntry> = mutableListOf()): Tab? {
        if (isAtCapacity) return null
        val tab = Tab(nextId++, webView, requestLog = requestLog)
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        return tab
    }

    fun closeTab(tabId: Int) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index == -1) return
        tabs[index].webView.apply {
            stopLoading()
            clearHistory()
            destroy()
        }
        tabs.removeAt(index)
        activeIndex = when {
            tabs.isEmpty() -> -1
            index <= activeIndex -> (activeIndex - 1).coerceAtLeast(0)
            else -> activeIndex
        }
    }

    fun switchTo(tabId: Int) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index != -1) activeIndex = index
    }

    fun destroyAll() {
        tabs.forEach {
            it.webView.apply {
                stopLoading()
                clearHistory()
                destroy()
            }
        }
        tabs.clear()
        activeIndex = -1
    }
}
