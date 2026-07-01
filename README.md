# Blackhole

A hardened Android browser with multiple operation modes and advanced features. Two tabs max. Nothing is logged. Nothing is saved. Built as a native Kotlin + WebView app (Android Studio project, Gradle build) so you have full control over the source.

**Debug APK location:** `app/build/outputs/apk/debug/app-debug.apk`

This project is dedicated to the public domain under the CC0 1.0 Universal
license. See `LICENSE` for details — you are free to copy, modify, distribute
and sell the code as you wish.

## Browser Modes

Blackhole now supports multiple operation modes, each with increasing levels of functionality. The current mode is displayed at the top of the app and can be changed in Settings. **All mode-specific data is session-scoped** — when you clear the session or close the app, you return to the Basic mode.

- **Basic (default)** — Standard browser with 2-tab limit, navigation, JavaScript toggle, ad blocking, notifications.
- **Intermediate** — Basic + Postman-like HTTP request tools (GET, POST, PUT, PATCH, DELETE), request headers/body editors, integrated request tester.
- **Advance** — Intermediate + JavaScript console (run arbitrary JS in page context), element inspector, expanded network logging.
- **ByteBandit** — Advance + advanced settings for cybersecurity professionals, SSH terminal integration, advanced proxy configurations.

## What's in here

```
blackhole/
├── app/
│   ├── src/main/java/com/blackhole/browser/
│   │   ├── MainActivity.kt              - UI wiring, tab rendering, mode indicator
│   │   ├── TabManager.kt                - enforces the 2-tab cap
│   │   ├── SecureWebView.kt             - WebView hardening + WebViewClient
│   │   ├── AdBlocker.kt                 - host-based request blocker
│   │   ├── BrowserMode.kt               - mode definitions (BASIC, INTERMEDIATE, ADVANCE, BYTEBANDIT)
│   │   ├── ModeManager.kt               - mode switching and feature availability
│   │   ├── DownloadManager.kt           - download file management with expiration
│   │   ├── HttpMethod.kt                - HTTP method enum
│   │   └── NotificationManager.kt       - organized notifications and alerts
│   ├── src/main/assets/
│   │   ├── homepage.html        - custom new-tab page
│   │   └── adblock_hosts.txt    - blocklist (starter seed)
│   ├── src/main/res/            - layouts, dark theme, strings
│   └── AndroidManifest.xml      - cleartext disabled, no backup, exported launcher only
└── ...gradle build files
```

## Key Features

### Mode-Based Interface
- **Mode Indicator** at the top shows your current mode
- **Quick Mode Switching** via Settings menu (each mode has its dedicated interface)
- **Persistent Settings** (security, proxy) survive mode changes and session resets
- **Session-Scoped Data** (console history, SSH sessions, downloads) cleared on session reset

### Advanced HTTP Tools (Intermediate mode)
- **Request Method Selector** — choose between GET, POST, PUT, PATCH, DELETE
- **Header Editor** — customize request headers
- **Body Editor** — set request bodies for POST/PUT/PATCH
- **Response Viewer** — see full response including status code and headers

### Developer Tools (Advance mode)
- **JavaScript Console** — execute arbitrary JS commands in the active page's context
- **Inspector** — inspect DOM elements and view element properties
- **Network Log** — detailed per-tab request logging (URLs, blocked/allowed status, timestamps)
- **Console History** — command history navigation

### Security & Privacy Features (All modes)
- **Download Manager** — saves downloads to `blackhole/downloads/` with configurable expiration (default: 24 hours)
- **Notification System** — organized, non-intrusive alerts across all features
- **Two security modes** (Strict HTTPS-only, or CTF mode for HTTP labs)
- **Per-tab JavaScript toggle** — off by default; toggle on per-tab when needed
- **Per-tab request log** — see all sub-resource requests, track what the ad blocker blocked
- **Proxy/Tor routing** — route all traffic through a SOCKS proxy (e.g., Orbot)
- **Always private** — no cookies, no DOM storage, no cache, no saved form data
- **Hard 2-tab cap** — minimal attack surface

## How to build the APK

You need Android Studio, or the command line with JDK 21 + Android SDK
installed. The repo now includes a Gradle wrapper, so you can build without a
system-wide Gradle install.

**Option A — Android Studio (easiest):**
1. Download this project folder.
2. Open Android Studio → "Open" → select the `blackhole` folder.
3. Let Gradle sync (it'll pull dependencies — needs internet).
4. Build → Build Bundle(s)/APK(s) → Build APK(s).
5. APK lands in `app/build/outputs/apk/debug/app-debug.apk`.
6. For a signed release build: Build → Generate Signed Bundle/APK, create a
   keystore when prompted, choose APK + release variant.

**Option B — command line:**
```bash
cd blackhole
./gradlew assembleDebug
# or for release (unsigned unless you configure signing):
./gradlew assembleRelease
```

If your Android SDK is not on the default path, set `ANDROID_SDK_ROOT` (or
`ANDROID_HOME`) before running Gradle. The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`.

- **Two security modes, your choice (Settings screen):**
  - **Strict** — HTTPS only, any `http://` navigation/sub-resource blocked.
  - **CTF Mode** — `http://` allowed, since lab/wargame boxes (TryHackMe,
    HTB, VulnHub, OverTheWire-style ranges) routinely serve plain HTTP only.
    Typing a bare IP/host with no scheme in CTF mode defaults to `http://`
    instead of `https://`, so it doesn't fail a TLS handshake against a box
    that isn't running TLS at all.
  - **TLS cert-error handling never changes between modes.** `onReceivedSslError`
    always calls `handler.cancel()` regardless of Strict/CTF — that's not a
    toggle, by design. CTF Mode is about the *scheme*, not about trusting
    bad certs.
- **No trust in sideloaded CAs.** `network_security_config.xml` only trusts
  system CAs, so a malicious VPN profile or corporate proxy cert can't
  silently intercept traffic.
- **Proxy / Tor routing toggle (Settings screen).** Routes all WebView
  traffic through a SOCKS proxy via `androidx.webkit.ProxyController` —
  this is a real, working proxy override, not a cosmetic switch. Defaults
  to Orbot's local SOCKS5 port (`127.0.0.1:9050`); point it at any SOCKS5
  proxy you run. **Be clear-eyed about what this does and doesn't do:**
  - It routes traffic through a fixed exit point (Tor circuit or proxy),
    so sites see that exit IP instead of yours.
  - It does **not** rotate your IP per request — that's not a thing an app
    can fake from inside its own process. If you want a new exit IP, you
    restart the Tor circuit (e.g. "New Identity" in Orbot) or point at a
    different proxy.
  - It's process-wide (affects every open tab at once), not per-tab.
  - 127.0.0.1/localhost is always bypassed even with the proxy on, so local
    CTF range targets on your own device/network still resolve directly.
- **Always private.** No cookies, no DOM storage, no cache, no saved
  form data/passwords, no `allowBackup`. Closing a tab destroys its WebView
  outright; closing the app wipes everything in `onDestroy()`.
- **Per-tab JavaScript toggle.** JS is off by default everywhere (still the
  big win against ad/exploit payloads), but the edit-icon button in the
  bottom bar flips it on/off for just the active tab when a site actually
  needs it (e.g. a CTF web challenge with client-side JS). State is tracked
  per tab, so switching tabs restores each tab's own setting.
- **Per-tab request log.** The history-icon button shows every sub-resource
  request the active tab has made, each tagged `BLOCKED` or `allowed`, newest
  first, capped at 200 entries per tab. Useful for recon - hidden endpoints,
  unexpected third-party calls, what the ad blocker actually caught.
- **Burp Suite proxy preset.** In Settings, the "Burp Suite" quick-fill button
  sets the proxy to `http://127.0.0.1:8080` (Burp's default listener) instead
  of manually typing it in. Start Burp's proxy listener first; if you want to
  see decrypted HTTPS traffic, import Burp's CA cert into Android's trusted
  certificates (Burp > Proxy > Import/export CA certificate).
- **JavaScript off by default.** Most ad/tracker/exploit payloads are JS.
  Sites work as static documents unless you wire up a per-site toggle (the
  `js_toggle` string is already scaffolded — happy to wire the UI for it if
  you want a "trust this site" button per tab).
- **No file:// access from web content**, no content provider access — this
  closes off a common local-file-exfiltration vector in WebViews.
- **Ad/tracker blocking at the network layer**, before requests ever leave
  the device — not just CSS hiding.
- **Hard 2-tab cap** — fewer concurrent contexts, smaller attack surface, no
  tab-bombing.

## Settings

Access settings via the gear icon in the top toolbar:

- **Security Mode** — Choose between Strict (HTTPS only) or CTF (HTTP allowed for lab environments)
- **Proxy Settings** — Enable/disable SOCKS proxy routing, configure host/port/scheme, or use Burp quick-fill
- **Browser Mode** — Select your operation mode (Basic, Intermediate, Advance, ByteBandit)
- **Download Retention** — Set how long downloaded files persist (default: 24 hours)
- **Notifications** — Configure notification preferences

## Improving the ad blocker

The seed list in `adblock_hosts.txt` is small. For real coverage, swap it for
a full hosts-style list, e.g. StevenBlack's unified hosts file (search
"stevenblack hosts github") — strip the `0.0.0.0 ` / `127.0.0.1 ` prefixes
and keep just the domain per line, same format already used here.

## Known limitations / roadmap

- SSH terminal integration (ByteBandit mode) — currently a placeholder; full integration with SSH client library pending.
- JavaScript console output formatting — basic output only; syntax highlighting TBD.
- Inspector element highlighting — currently read-only; interactive highlighting TBD.
- Icon design — placeholder vector; Lucide icons TBD.

---

## Documentation

A more detailed developer guide is available at `docs/DOCUMENTATION.md`. It describes the repository layout, important files to edit (homepage, WebView settings, adblock list), and step-by-step build and install instructions.
