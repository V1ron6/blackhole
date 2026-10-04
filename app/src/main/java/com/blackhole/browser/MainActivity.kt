package com.blackhole.browser

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebStorage
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

    // Shared file-picker plumbing for tools that need to read an arbitrary
    // file (File Inspector, Strings Extractor). The launcher must be
    // registered here, before STARTED, per ActivityResultContracts rules -
    // the tool dialogs themselves are plain objects, not Activities/Fragments,
    // so they can't register their own. pickFile() is what they call instead.
    private var pendingFilePickedCallback: ((android.net.Uri) -> Unit)? = null
    private lateinit var filePickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>

    /** Opens the system file picker; invokes [onPicked] with the chosen content Uri. */
    fun pickFile(onPicked: (android.net.Uri) -> Unit) {
        pendingFilePickedCallback = onPicked
        filePickerLauncher.launch(arrayOf("*/*"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filePickerLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { pendingFilePickedCallback?.invoke(it) }
            pendingFilePickedCallback = null
        }

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

        updateSessionBadge()
        binding.sessionBadge.setOnClickListener {
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
            onUrlChanged = { url ->
                if (isActiveWebView(webView)) {
                    binding.urlBar.setText(url)
                    updateSiteBlockIcon()
                }
            },
            onLoadingChanged = { /* could wire a progress bar here */ },
            onBlockedInsecure = {
                Toast.makeText(this, getString(R.string.insecure_connection), Toast.LENGTH_SHORT).show()
            },
            onRequestLogged = { host, url, method, blocked ->
                requestLog.add(0, RequestLogEntry(host, url, method, blocked, System.currentTimeMillis()))
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

                val saved = downloadManager.saveDownload(fileName, data, mimeType)
                runOnUiThread {
                    if (saved != null) {
                        NotificationManager.showToast(this, "Saved $fileName to Downloads/Blackhole")
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
        updateSiteBlockIcon()
    }

    private fun updateJsToggleIcon() {
        val active = tabManager.activeTab()
        val enabled = active?.jsEnabled == true
        binding.btnJsToggle.imageTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (enabled) R.color.bh_accent else R.color.bh_text_dim)
        )
    }

    /** Red shield = this exact host is currently blocked (by the user or the static list). */
    private fun updateSiteBlockIcon() {
        val host = tabManager.activeTab()?.webView?.url?.let { android.net.Uri.parse(it).host }
        val blocked = adBlocker.isBlocked(host)
        binding.btnSiteBlock.imageTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (blocked) R.color.bh_danger else R.color.bh_text_dim)
        )
    }

    private fun toggleSiteBlock() {
        val active = tabManager.activeTab() ?: return
        val host = active.webView.url?.let { android.net.Uri.parse(it).host }
        if (host.isNullOrEmpty()) {
            Toast.makeText(this, "No site loaded to block", Toast.LENGTH_SHORT).show()
            return
        }
        val nowBlocked = adBlocker.toggleHost(host)
        updateSiteBlockIcon()
        Toast.makeText(
            this,
            if (nowBlocked) "Blocked $host" else "Unblocked $host - reload to see it",
            Toast.LENGTH_SHORT
        ).show()
        if (nowBlocked) active.webView.reload()
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
        binding.btnSiteBlock.setOnClickListener {
            toggleSiteBlock()
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
        // Six categories, same grouping as the docs site's toolbox section -
        // keeps "what's in CTF Toolkit" etc. consistent between the app and
        // the website. Order here is also the order categories are listed in.
        val categories = linkedMapOf<String, MutableList<Pair<String, () -> Unit>>>(
            "Recon" to mutableListOf(),
            "Testing" to mutableListOf(),
            "Dev Tools" to mutableListOf(),
            "Data & Crypto" to mutableListOf(),
            "CTF Toolkit" to mutableListOf(),
            "Export" to mutableListOf()
        )

        fun add(category: String, label: String, action: () -> Unit) {
            categories.getValue(category).add(label to action)
        }

        // --- Recon ---------------------------------------------------------
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.SECURITY_SCANNER)) {
            add("Recon", "Security Headers") {
                SecurityHeaderScanner.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.INSPECTOR)) {
            add("Recon", "TLS Certificate") {
                TlsInspector.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.WHOIS_LOOKUP)) {
            add("Recon", "WHOIS Lookup") {
                WhoisLookup.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.DNS_LOOKUP)) {
            add("Recon", "DNS Lookup") {
                DnsLookup.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.TECH_FINGERPRINT)) {
            add("Recon", "Tech Fingerprint") {
                TechFingerprint.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.FAVICON_HASH)) {
            add("Recon", "Favicon Hash") {
                FaviconHash.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.ROBOTS_FETCH)) {
            add("Recon", "robots.txt / sitemap.xml") {
                RobotsFetch.show(this, tabManager.activeTab()?.webView)
            }
        }

        // --- Testing (live target) ------------------------------------------
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.POSTMAN_REQUESTS)) {
            add("Testing", "Postman Request") { PostmanRequestDialog.show(this) }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.DIRECTORY_BUSTER)) {
            add("Testing", "Directory Buster") {
                DirectoryBuster.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.PORT_SCANNER)) {
            add("Testing", "Port Scanner") {
                PortScanner.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.GRAPHQL_INTROSPECTION)) {
            add("Testing", "GraphQL Introspection") {
                GraphQLIntrospection.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.SSH_TERMINAL)) {
            add("Testing", "SSH Terminal") { SSHTerminal.show(this) }
        }

        // --- Dev Tools -------------------------------------------------------
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.JS_CONSOLE)) {
            add("Dev Tools", "JavaScript Console") {
                JavaScriptConsole.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.COOKIE_INSPECTOR)) {
            add("Dev Tools", "Cookie Inspector") {
                CookieInspector.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.STORAGE_INSPECTOR)) {
            add("Dev Tools", "Storage Inspector") {
                StorageInspector.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.USER_AGENT_SWITCHER)) {
            add("Dev Tools", "User-Agent Switcher") {
                UserAgentSwitcher.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.DIFF_VIEWER)) {
            add("Dev Tools", "Diff Viewer") { DiffViewer.show(this) }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.REQUEST_TIMELINE)) {
            add("Dev Tools", "Request Timeline") {
                RequestTimeline.show(this, tabManager.activeTab()?.requestLog ?: emptyList())
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.VIEWPORT_EMULATOR)) {
            add("Dev Tools", "Viewport Emulator") {
                ViewportEmulator.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.ACCESSIBILITY_CHECKER)) {
            add("Dev Tools", "Accessibility Scan") {
                AccessibilityChecker.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.SCREENSHOT_CAPTURE)) {
            add("Dev Tools", "Screenshot") {
                ScreenshotCapture.show(this, tabManager.activeTab()?.webView, downloadManager)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.BOOKMARKLET_RUNNER)) {
            add("Dev Tools", "Bookmarklets") {
                BookmarkletRunner.show(this, tabManager.activeTab()?.webView)
            }
        }

        // --- Data & Crypto -----------------------------------------------------
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.JWT_DECODER)) {
            add("Data & Crypto", "JWT Decoder") { JwtDecoder.show(this) }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.JSON_FORMATTER)) {
            add("Data & Crypto", "JSON Formatter") { JsonFormatter.show(this) }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.REGEX_TESTER)) {
            add("Data & Crypto", "Regex Tester") { RegexTester.show(this) }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.DATA_TOOLKIT)) {
            add("Data & Crypto", "Data Toolkit") { DataToolkit.show(this) }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.TOTP_GENERATOR)) {
            add("Data & Crypto", "TOTP Generator") { TotpGenerator.show(this) }
        }

        // --- CTF Toolkit -------------------------------------------------------
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.HASH_IDENTIFIER)) {
            add("CTF Toolkit", "Hash Identifier") { HashIdentifier.show(this) }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.HASH_CRACKER)) {
            add("CTF Toolkit", "Hash Cracker (JtR-lite)") { HashCracker.show(this) }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.HYDRA_LITE)) {
            add("CTF Toolkit", "Credential Brute Forcer (Hydra-lite)") {
                HydraLite.show(this, tabManager.activeTab()?.webView)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.CIPHER_SOLVER)) {
            add("CTF Toolkit", "Cipher Solver") { CipherSolver.show(this) }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.FILE_INSPECTOR)) {
            add("CTF Toolkit", "File Inspector") { FileInspector.show(this, ::pickFile) }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.STRINGS_EXTRACTOR)) {
            add("CTF Toolkit", "Strings Extractor") { StringsExtractor.show(this, ::pickFile) }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.EXPLOIT_SHELL)) {
            add("CTF Toolkit", "Exploit Shell (Experimental)") { ExploitShell.show(this) }
        }

        // --- Export ----------------------------------------------------------
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.HAR_EXPORT)) {
            add("Export", "Export Request Log (HAR)") {
                HarExport.export(this, tabManager.activeTab()?.requestLog ?: emptyList(), downloadManager)
            }
        }
        if (modeManager.isFeatureAvailable(ModeManager.ModeFeature.CASE_FILE_EXPORT)) {
            add("Export", "Export Case File") {
                CaseFileExport.export(this, tabManager.activeTab()?.requestLog ?: emptyList(), downloadManager)
            }
        }

        val nonEmpty = categories.filterValues { it.isNotEmpty() }

        if (nonEmpty.isEmpty()) {
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

        // Single category (e.g. Intermediate mode only unlocks Postman, which
        // lands in "Testing") - skip the category-picker step, go straight in.
        if (nonEmpty.size == 1) {
            val (name, tools) = nonEmpty.entries.first()
            AlertDialog.Builder(this)
                .setTitle("$name (${modeManager.getCurrentMode().displayName} mode)")
                .setItems(tools.map { it.first }.toTypedArray()) { _, index -> tools[index].second() }
                .setNegativeButton("Close", null)
                .show()
            return
        }

        fun showCategoryList() {
            val categoryNames = nonEmpty.keys.toList()
            val labels = categoryNames.map { name -> "$name (${nonEmpty.getValue(name).size})" }
            AlertDialog.Builder(this)
                .setTitle("Tools (${modeManager.getCurrentMode().displayName} mode)")
                .setItems(labels.toTypedArray()) { _, index ->
                    val categoryName = categoryNames[index]
                    val tools = nonEmpty.getValue(categoryName)
                    AlertDialog.Builder(this)
                        .setTitle("$categoryName (${modeManager.getCurrentMode().displayName} mode)")
                        .setItems((listOf("\u2039 Back to categories") + tools.map { it.first }).toTypedArray()) { _, toolIndex ->
                            if (toolIndex == 0) showCategoryList() else tools[toolIndex - 1].second()
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
                .setNegativeButton("Close", null)
                .show()
        }

        showCategoryList()
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
        listView.setBackgroundColor(getColor(R.color.bh_background))
        listView.adapter = ArrayAdapter(this, R.layout.list_item_dark, rows)

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
        WebStorage.getInstance().deleteAllData()
        clearCache()
        downloadManager.clearAll()
        modeManager.resetToBasicMode()
        // Session Manager never silently survives a clear - if it was on for
        // a CTF login, this session is now over; the next one needs a fresh
        // explicit opt-in.
        settings.sessionPersistenceEnabled = false
        updateSessionBadge()
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

    /**
     * Session Manager badge - only visible when cookies/localStorage are
     * being kept, so persistence is never on without a visible reminder.
     * Hidden (not just quiet) the rest of the time, since it's the
     * exception, not the normal state.
     */
    private fun updateSessionBadge() {
        binding.sessionBadge.visibility =
            if (settings.sessionPersistenceEnabled) android.view.View.VISIBLE else android.view.View.GONE
    }

    // --- Lifecycle: enforce "always private, nothing survives exit" -------

    override fun onResume() {
        super.onResume()
        ProxyManager.applyFromSettings(settings)
        modeManager.syncFromSettings()
        updateSessionBadge()
        syncSessionPersistenceToOpenTabs()
    }

    /**
     * Applies the current Session Manager setting to CookieManager (global,
     * takes effect immediately either way) and to every already-open tab's
     * WebView (domStorageEnabled is per-WebView, so it doesn't retroactively
     * apply on its own - a tab created before the setting was turned on
     * would otherwise keep rejecting localStorage forever, even after the
     * global cookie flag changes). This is what was missing before: toggling
     * Session Manager in Settings only ever wrote the SharedPreferences
     * value: nothing re-applied it to a tab you already had open, which is
     * exactly the "log in, immediately logged out" symptom on sites that
     * lean on localStorage for auth state, not just cookies. Called from
     * onResume so returning from Settings always re-syncs, regardless of
     * whether the toggle actually changed.
     */
    private fun syncSessionPersistenceToOpenTabs() {
        val enabled = settings.sessionPersistenceEnabled
        CookieManager.getInstance().setAcceptCookie(enabled)
        tabManager.allTabs().forEach { tab ->
            tab.webView.settings.domStorageEnabled = enabled
        }
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
