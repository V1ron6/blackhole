package com.blackhole.browser

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.blackhole.browser.databinding.ActivityMainBinding
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adBlocker: AdBlocker
    private lateinit var settings: Settings
    private lateinit var modeManager: ModeManager
    private lateinit var downloadManager: DownloadManager
    private val tabManager = TabManager()
    private val HOME_URL = "file:///android_asset/homepage.html"
    private val MAX_LOG_ENTRIES = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adBlocker = AdBlocker(this)
        settings = Settings(this)
        modeManager = ModeManager(this, settings)
        downloadManager = DownloadManager(this, settings)
        ProxyManager.applyFromSettings(settings)
        
        // Clean up expired downloads on app start
        downloadManager.cleanupExpiredDownloads()
        
        // Setup mode indicator listener
        modeManager.addModeListener { mode ->
            updateModeIndicator(mode)
        }
        
        // Initial mode indicator update
        updateModeIndicator(modeManager.getCurrentMode())
        binding.modeIndicatorBar.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        // Always-private: wipe any leftover cookies/cache from a prior process at launch.
        CookieManager.getInstance().removeAllCookies(null)
        clearCache()

        setupUrlBar()
        setupNavButtons()

        // Start with a single tab on the homepage.
        openNewTab()
    }

    // --- Tab creation / rendering -----------------------------------------

    private fun openNewTab() {
        if (tabManager.isAtCapacity) {
            Toast.makeText(this, getString(R.string.max_tabs_warning), Toast.LENGTH_SHORT).show()
            return
        }
        val webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // shouldInterceptRequest fires on a background thread, so the log list
        // backing it must be thread-safe.
        val requestLog = Collections.synchronizedList(mutableListOf<RequestLogEntry>())
        SecureWebView.create(
            webView = webView,
            adBlocker = adBlocker,
            appSettings = settings,
            jsEnabled = false,
            onUrlChanged = { url -> if (isActiveWebView(webView)) binding.urlBar.setText(url) },
            onLoadingChanged = { /* could wire a progress bar here */ },
            onBlockedInsecure = {
                Toast.makeText(this, getString(R.string.insecure_connection), Toast.LENGTH_SHORT).show()
            },
            onRequestLogged = { host, blocked ->
                requestLog.add(0, RequestLogEntry(host, blocked, System.currentTimeMillis()))
                // Cap log size per tab so a chatty page can't grow this unbounded.
                if (requestLog.size > MAX_LOG_ENTRIES) {
                    requestLog.removeAt(requestLog.size - 1)
                }
            }
        )
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
        binding.webViewContainer.addView(webView)
        val tab = tabManager.addTab(webView, requestLog)
        if (tab != null) {
            webView.loadUrl(HOME_URL)
            renderTabIndicators()
            showOnlyActiveWebView()
        }
    }

    // --- Downloads -----------------------------------------------------

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        NotificationManager.showToast(this, "Downloading $fileName\u2026")
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                userAgent?.let { connection.setRequestProperty("User-Agent", it) }
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.connect()

                if (connection.responseCode !in 200..299) {
                    throw java.io.IOException("HTTP ${connection.responseCode}")
                }

                val maxBytes = DownloadManager.MAX_FILE_SIZE
                val data = connection.inputStream.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var total = 0
                    var read: Int
                    while (input.read(chunk).also { read = it } != -1) {
                        total += read
                        if (total > maxBytes) throw java.io.IOException("File exceeds ${maxBytes / (1024 * 1024)}MB limit")
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
                connection.disconnect()

                val saved = downloadManager.saveDownload(fileName, data)
                runOnUiThread {
                    if (saved != null) {
                        NotificationManager.showToast(this, "Saved $fileName to blackhole/downloads")
                    } else {
                        NotificationManager.showToast(this, "Failed to save $fileName")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    NotificationManager.showToast(this, "Download failed: ${e.message}")
                }
            }
        }.start()
    }

    private fun isActiveWebView(webView: WebView): Boolean =
        tabManager.activeTab()?.webView === webView

    private fun closeActiveTab() {
        val active = tabManager.activeTab() ?: return
        binding.webViewContainer.removeView(active.webView)
        tabManager.closeTab(active.id)
        if (tabManager.size == 0) {
            openNewTab()
        } else {
            renderTabIndicators()
            showOnlyActiveWebView()
        }
    }

    private fun switchToTab(tabId: Int) {
        tabManager.switchTo(tabId)
        renderTabIndicators()
        showOnlyActiveWebView()
    }

    private fun showOnlyActiveWebView() {
        val active = tabManager.activeTab()
        for (tab in tabManager.allTabs()) {
            tab.webView.visibility =
                if (tab.id == active?.id) android.view.View.VISIBLE else android.view.View.GONE
        }
        binding.urlBar.setText(active?.webView?.url.orEmpty().let {
            if (it.startsWith("file://")) "" else it
        })
        updateJsToggleIcon()
    }

    private fun updateJsToggleIcon() {
        val active = tabManager.activeTab()
        val enabled = active?.jsEnabled == true
        binding.btnJsToggle.imageTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (enabled) R.color.bh_accent else R.color.bh_text_dim)
        )
    }

    private fun renderTabIndicators() {
        binding.tabIndicatorContainer.removeAllViews()
        val active = tabManager.activeTab()
        tabManager.allTabs().forEachIndexed { index, tab ->
            val pill = layoutInflater.inflate(
                R.layout.item_tab_pill, binding.tabIndicatorContainer, false
            ) as TextView
            pill.text = (index + 1).toString()
            pill.isSelected = tab.id == active?.id
            pill.setOnClickListener { switchToTab(tab.id) }
            pill.setOnLongClickListener {
                tabManager.switchTo(tab.id)
                closeActiveTab()
                true
            }
            binding.tabIndicatorContainer.addView(pill)
        }
    }

    // --- URL bar -------------------------------------------------------

    private fun setupUrlBar() {
        binding.urlBar.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isGo) {
                navigate(binding.urlBar.text.toString())
                true
            } else {
                false
            }
        }
        binding.btnNewTab.setOnClickListener { openNewTab() }
        binding.btnSettings.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
    }

    private fun navigate(input: String) {
        if (input.isBlank()) return
        val resolved = SecureWebView.resolveInput(input, settings.securityMode)
        tabManager.activeTab()?.webView?.loadUrl(resolved)
        currentFocus?.let {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    // --- Nav buttons -----------------------------------------------------

    private fun setupNavButtons() {
        binding.btnBack.setOnClickListener {
            tabManager.activeTab()?.webView?.let { wv -> if (wv.canGoBack()) wv.goBack() }
        }
        binding.btnForward.setOnClickListener {
            tabManager.activeTab()?.webView?.let { wv -> if (wv.canGoForward()) wv.goForward() }
        }
        binding.btnReload.setOnClickListener {
            tabManager.activeTab()?.webView?.reload()
        }
        binding.btnHome.setOnClickListener {
            tabManager.activeTab()?.webView?.loadUrl(HOME_URL)
        }
        binding.btnClearSession.setOnClickListener {
            clearEverythingAndReset()
        }
        binding.btnJsToggle.setOnClickListener {
            toggleJsForActiveTab()
        }
        binding.btnRequestLog.setOnClickListener {
            showRequestLog()
        }
        binding.btnTools.setOnClickListener {
            showToolsMenu()
        }
    }

    private fun toggleJsForActiveTab() {
        val active = tabManager.activeTab() ?: return
        active.jsEnabled = !active.jsEnabled
        active.webView.settings.javaScriptEnabled = active.jsEnabled
        updateJsToggleIcon()
        Toast.makeText(
            this,
            if (active.jsEnabled) "JavaScript ON for this tab" else "JavaScript OFF for this tab",
            Toast.LENGTH_SHORT
        ).show()
        active.webView.reload()
    }

    private fun showToolsMenu() {
        val available = mutableListOf<Pair<String, () -> Unit>>()
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.POSTMAN_REQUESTS)) {
            available.add("Postman Request" to { PostmanRequestDialog.show(this) })
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.JS_CONSOLE)) {
            available.add("JavaScript Console" to {
                JavaScriptConsole.show(this, tabManager.activeTab()?.webView)
            })
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.SSH_TERMINAL)) {
            available.add("SSH Terminal" to { SSHTerminal.show(this) })
        }

        if (available.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Tools")
                .setMessage("No tools available in Basic mode. Switch to Intermediate, Advance, or ByteBandit mode in Settings to unlock them.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(android.content.Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("Close", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Tools (${modeManager.getCurrentMode().displayName} mode)")
            .setItems(available.map { it.first }.toTypedArray()) { _, index ->
                available[index].second()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRequestLog() {
        val active = tabManager.activeTab() ?: return
        val entries = active.requestLog.toList()
        if (entries.isEmpty()) {
            Toast.makeText(this, "No requests logged yet for this tab", Toast.LENGTH_SHORT).show()
            return
        }
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        val rows = entries.map { entry ->
            val time = timeFormat.format(Date(entry.timestampMillis))
            val status = if (entry.blocked) "BLOCKED" else "allowed"
            "[$time] $status  ${entry.host}"
        }

        val listView = ListView(this)
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)

        AlertDialog.Builder(this)
            .setTitle("Request log (${entries.size}, this tab)")
            .setView(listView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ -> active.requestLog.clear() }
            .show()
    }

    private fun clearCache() {
        // Per-tab caches are also disabled at creation; this clears anything
        // persisted to disk from a previous run that wasn't created via SecureWebView.
        WebView(this).apply {
            clearCache(true)
            clearHistory()
            clearFormData()
            destroy()
        }
    }

    private fun clearEverythingAndReset() {
        tabManager.destroyAll()
        binding.webViewContainer.removeAllViews()
        CookieManager.getInstance().removeAllCookies(null)
        clearCache()
        downloadManager.clearAll()
        modeManager.resetToBasicMode()
        renderTabIndicators()
        openNewTab()
        NotificationManager.showToast(this, "Session cleared and reset to Basic mode")
    }

    private fun updateModeIndicator(mode: BrowserMode) {
        binding.modeIndicatorText.text = "Mode: ${mode.displayName}"
        val colorRes = when (mode) {
            BrowserMode.BASIC -> R.color.bh_text_dim
            BrowserMode.INTERMEDIATE -> R.color.bh_accent
            BrowserMode.ADVANCE -> R.color.bh_warning
            BrowserMode.BYTEBANDIT -> R.color.bh_danger
        }
        binding.modeIndicatorText.setTextColor(getColor(colorRes))
    }

    // --- Lifecycle: enforce "always private, nothing survives exit" -------

    override fun onResume() {
        super.onResume()
        ProxyManager.applyFromSettings(settings)
        modeManager.syncFromSettings()
    }

    override fun onDestroy() {
        tabManager.destroyAll()
        CookieManager.getInstance().removeAllCookies(null)
        clearCache()
        super.onDestroy()
    }

    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        val wv = tabManager.activeTab()?.webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
