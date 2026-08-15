# Blackhole — Developer Documentation

This document explains the repository layout, what each major folder/file does, where to make common edits, and how to build and install the app.

## Quick start

- Build debug APK (local machine with Android SDK & JDK 21):

```bash
cd blackhole
./gradlew assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk
```

- Install to a connected device/emulator (requires `adb`):

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
# or let Gradle install to a running device:
./gradlew installDebug
```

Environment notes:
- Set `ANDROID_SDK_ROOT` or `ANDROID_HOME` if your SDK is in a non-standard location.
- This project builds with JDK 21 in the dev container used by the repository.

## Repository layout and what each folder means

Top-level:
- `build.gradle.kts`, `settings.gradle.kts`, `gradlew` / `gradlew.bat` — Gradle build and wrapper.
- `README.md` — high-level project README. This file links to the developer docs at `docs/DOCUMENTATION.md`.

`app/` — the Android application module. Key subfolders:
- `src/main/java/com/blackhole/browser/` — Kotlin source code for the app.
  - `MainActivity.kt` — main UI wiring: URL bar, navigation buttons, tab management integration, mode indicator.
  - `SecureWebView.kt` — WebView hardening and navigation resolution. Contains `resolveInput()` which decides whether user input is a URL or a search term. Also wires the WebViewClient handlers (scheme handling, SSL errors, request interception).
  - `Settings.kt` — SharedPreferences wrapper for user settings (security mode, proxy settings, browser mode, download retention).
  - `SettingsActivity.kt` — Settings screen UI and persistence.
  - `TabManager.kt` — lightweight 2-tab model.
  - `AdBlocker.kt` — host-based request blocking logic.
  - **New files (Mode System):**
    - `BrowserMode.kt` — enum defining browser modes (BASIC, INTERMEDIATE, ADVANCE, BYTEBANDIT).
    - `ModeManager.kt` — manages mode transitions and feature availability per mode.
    - `HttpMethod.kt` — enum for HTTP methods (GET, POST, PUT, PATCH, DELETE).
    - `NotificationManager.kt` — centralized notification and alert management (toasts, dialogs, confirms).
    - `DownloadManager.kt` — handles file downloads, expiration cleanup, and session-scoped management.

- `src/main/assets/` — static assets loaded by the app.
  - `homepage.html` — the new-tab page shown on app start. Contains the search field UI, quick links, and settings shortcuts. The homepage now resolves searches locally (DuckDuckGo by default) and opens direct URLs without requiring the app to intercept a custom scheme.
  - `adblock_hosts.txt` — seed hostlist for the `AdBlocker`. One domain per line.

- `src/main/res/` — Android resources: layouts, colors, strings.
  - `layout/activity_main.xml` — main app layout (top URL bar, WebView container, bottom toolbar).
  - `layout/activity_settings.xml` — settings layout used by `SettingsActivity`.

- `src/main/AndroidManifest.xml` — app manifest; declares `SettingsActivity` and the launcher activity.

## Key editing points

- Change homepage content / UI
  - File: `app/src/main/assets/homepage.html`.
  - Notes: The search field now runs a small resolver in the page (`isLikelyUrl()` / `resolveSearchTarget()` in the script). URLs are sent directly to the WebView (prefixed with `https://` if needed). Non-URL queries are sent to DuckDuckGo by default. To change the default search engine, update the URL returned by `resolveSearchTarget()`.

- Change search resolution in the application code
  - File: `app/src/main/java/com/blackhole/browser/SecureWebView.kt`.
  - Notes: `resolveInput(input, securityMode)` is the canonical resolver used by the app's URL bar and by internal navigation. It converts typed input into either a URL (with scheme) or a DuckDuckGo search URL. Edit here to change default search behavior or URL heuristics for the app-wide URL bar.

- Handle homepage deep links
  - The homepage contains `blackhole://settings` shortcuts. `SecureWebView` now recognizes the `blackhole://` scheme and opens `SettingsActivity` on `blackhole://settings`. You can add more custom handlers in `shouldOverrideUrlLoading()` if needed.

- Proxy / Tor settings
  - Files: `Settings.kt`, `SettingsActivity.kt`, `ProxyManager.kt`.
  - Notes: Use `SettingsActivity` to change proxy options. `ProxyManager.applyFromSettings()` applies them via androidx.webkit's proxy override APIs. The defaults are set for Orbot (socks5 `127.0.0.1:9050`) and a Burp preset is available in the UI.

- Ad blocker
  - File: `app/src/main/assets/adblock_hosts.txt` and `AdBlocker.kt`.
  - Notes: The ad blocker expects one domain per line. To improve coverage, replace the seed with a fuller hosts file (e.g. StevenBlack's lists), keeping only domain names (no `0.0.0.0` prefixes).

## Browser Mode System

Blackhole now supports a multi-mode interface with progressively more features:

- **Mode Management** (`ModeManager.kt`)
  - `setMode(mode)` — switch to a new mode (persisted to settings).
  - `getCurrentMode()` — get the active mode.
  - `isFeatureAvailable(feature)` — check if a feature is available in the current mode.
  - `resetToBasicMode()` — called on session clear to return to BASIC mode.
  - Modes are persisted to SharedPreferences but revert to BASIC on app restart (session-based).

- **Mode Definitions** (`BrowserMode.kt`)
  - `BASIC` — standard browser (default).
  - `INTERMEDIATE` — adds Postman-like HTTP request tools.
  - `ADVANCE` — adds JavaScript console and inspector.
  - `BYTEBANDIT` — adds advanced cybersecurity tools (SSH terminal, advanced settings).

- **Mode-Specific UI Updates** (in `MainActivity.kt`)
  - Mode changes trigger listeners registered with `ModeManager.addModeListener()`.
  - UI elements are shown/hidden based on `ModeManager.isFeatureAvailable()`.
  - `updateModeIndicator(mode)` displays the current mode at the top of the app.

## Download Manager

The `DownloadManager.kt` class handles file downloads and expiration:

- **Save downloads** — `saveDownload(fileName, bytes)` saves to the app's `downloads/` directory.
- **Retrieve downloads** — `getDownloads()` lists all saved files.
- **Cleanup** — `cleanupExpiredDownloads()` deletes files older than the retention period (from `Settings.downloadRetentionHours`).
- **Session clear** — `clearAll()` deletes all downloads immediately (called on session reset).
- **File sanitization** — `sanitizeFileName()` prevents directory traversal attacks.

To integrate downloads into the WebView, override the download handler in `SecureWebView.kt` and call `downloadManager.saveDownload()` with the file data.

## Notifications

The `NotificationManager.kt` object provides centralized notification management:

- `showToast(activity, message, duration)` — quick toast notification.
- `showAlert(activity, title, message, onDismiss)` — dialog alert with callback.
- `showConfirm(activity, title, message, onConfirm, onCancel)` — confirmation dialog.

Used throughout the app for user-facing messages; can be easily extended for in-app notification bars or custom notifications.

## HTTP Methods

The `HttpMethod.kt` enum defines supported HTTP verbs for use in Intermediate mode and above:

- `GET`, `POST`, `PUT`, `PATCH`, `DELETE`

Extend this as needed for other methods (e.g., `HEAD`, `OPTIONS`).

## Session Data Scope

Important distinction between persistent and session-scoped data:

- **Persistent** (survive session clear & app restart):
  - Browser mode setting (but reverts to BASIC on process restart for safety)
  - Security mode (Strict vs. CTF)
  - Proxy settings
  - Download retention time setting

- **Session-Scoped** (cleared on session reset or app exit):
  - Downloaded files (expire after retention time, not persisted across sessions)
  - JavaScript console history
  - SSH sessions
  - Notification queue
  - Tab state and navigation history

## Build, run, and compile

- Build debug APK:
  - `./gradlew assembleDebug`
  - Output: `app/build/outputs/apk/debug/app-debug.apk`

- Install to device (local):
  - `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  - If `adb` is not installed in your environment, install the Android SDK Platform Tools or run from Android Studio's Device Manager.

- Compile Kotlin only (fast check):
  - `./gradlew :app:compileDebugKotlin`

## Customizing and extending

- Add a settings option:
  - Add a new property to `Settings.kt` (backed by SharedPreferences).
  - Add the UI control in `activity_settings.xml` and wire it in `SettingsActivity.kt`.
  - If the setting affects WebView behavior, make sure to call the appropriate apply method (e.g., `ProxyManager.applyFromSettings()`), then reload or recreate affected tabs.

- Add a mode-specific feature:
  - Define the feature in `ModeManager.ModeFeature` enum.
  - Add a check to `isFeatureAvailable()` for the required mode(s).
  - Wire the UI visibility in `MainActivity` using `modeManager.isFeatureAvailable()`.
  - Add a mode listener if the feature needs to react to mode changes: `modeManager.addModeListener { mode -> /* update UI */ }`.

- Add a new browser mode:
  - Add variant to `BrowserMode.kt` enum.
  - Update `ModeManager.isFeatureAvailable()` to define which features are available in the new mode.
  - Update UI in `MainActivity` to reflect the new mode if needed.

- Add a homepage card or link:
  - Edit `homepage.html` and add anchors or cards. Use `blackhole://settings` or other `blackhole://` host values to call back into the app for deep links.

- Integrate downloads:
  - In `SecureWebView.kt`, override the download handler (e.g., `webView.setDownloadListener()`).
  - Call `downloadManager.saveDownload(fileName, fileData)` to persist the file.
  - Use `downloadManager.getDownloads()` to list saved files in a UI element.

## Troubleshooting

- `adb: command not found` — install Android Platform Tools or use Android Studio emulator.
- Gradle build failures often stem from missing Android SDK or mismatched `compileSdkVersion` — ensure your `ANDROID_SDK_ROOT` points to a valid SDK with matching platforms installed.

## Contributing

- Keep changes minimal and focused. Prefer modifying the root behavior (e.g., `SecureWebView.resolveInput()`) rather than applying many UI-only workarounds.
- Follow Kotlin style and Android resource conventions when editing app code.

If you want, I can also add a short developer checklist or a small CONTRIBUTING.md. Tell me which sections you'd like expanded.
