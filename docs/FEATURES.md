# Blackhole — Feature Roadmap

This is the running list of features planned for Blackhole, organized by
which browser mode they'd unlock in. Most new tools land in **Advance** or
**ByteBandit** — Basic and Intermediate stay lean by design.

Nothing here is built yet unless marked ✅. This is a planning document, not
a changelog.

---

## Basic
Standard browsing. No additions planned here — this tier stays minimal on purpose.

## Intermediate
HTTP tooling for testing your own endpoints.

- ✅ Postman-like request builder (method, headers, body, response viewer)
- cURL export — turn any request in the log, or the Postman dialog, into a
  copy-pasteable `curl` command
- Request history — save the last N requests so you're not retyping them

## Advance
Developer- and recon-facing tools. All 18 planned tools are now implemented.

- ✅ JavaScript console (evaluate JS in the active tab's page context)
- ✅ Cookie inspector — lists cookies visible to the active tab. Note:
  Blackhole disables cookies globally by default, so this mostly serves as
  a "confirm nothing is being set" check rather than live session
  inspection, unless a per-tab cookie opt-in gets built later
- ✅ Storage inspector — view localStorage/sessionStorage for the current page
- ✅ Security-header scanner — checks the current page's response for
  `Content-Security-Policy`, `X-Frame-Options`, `Strict-Transport-Security`,
  `Referrer-Policy`, `X-Content-Type-Options`, `Permissions-Policy`, shown
  as pass/fail per header
- ✅ TLS/certificate inspector — issuer, subject, validity window, and SANs
  for the active page's cert chain, flags expired/expiring-soon certs
- ✅ Request Timeline — chronological view of the tab's request log (host,
  blocked/allowed, timing gaps). Not a true status+size waterfall: the
  underlying log only ever captures host/url/method/timestamp, since it's
  built at request-interception time before any response exists
- ✅ Viewport Emulator — resizes the active WebView to common breakpoints
  (mobile/tablet/desktop). Size-only preview, not a full device emulator
- ✅ Accessibility Scan — structural DOM scan (missing alt text, unlabeled
  form fields, missing lang/title, unnamed links). No contrast/color
  checks - those need rendered pixel data this can't get from the DOM
- ✅ JSON formatter/validator with a path query box (own dialog). Path
  query is a hand-rolled dot/bracket subset, not full JSONPath
- ✅ Regex tester against sample text (own dialog) - ignore-case/
  multiline/dot-all toggles, lists match positions and capture groups
- ✅ Diff viewer — line-based LCS diff between two pasted blobs, local only
- ✅ Data Toolkit — Base64 / URL / Hex encode-decode and MD5/SHA-1/SHA-256/
  SHA-512 hashing, all local, no network call
- ✅ JWT decoder — header/payload split, expiry ("exp" claim) highlighted.
  Decoding only, not signature verification - there's no key entry here
- ✅ User-agent switcher — per-tab UA override with common presets + custom
- ✅ Screenshot — captures the visible viewport via View.draw(Canvas),
  saved as PNG to Downloads/Blackhole. NOT full-page: an earlier version of
  this doc/code claimed a full-page capture via a WebView.captureBitmapAsync
  method that turned out not to exist in the public SDK - caught by a build
  error and corrected. A true full-page capture would need scroll-and-stitch,
  which isn't implemented
- ✅ Bookmarklet runner — save/run small JS snippets, persisted locally
  across app restarts
- ✅ Tech-stack fingerprinting — signature matching (headers + HTML) for
  common CMSes, frameworks, CDNs, servers. A curated hand-rolled set, not
  a Wappalyzer-scale database
- ✅ robots.txt / sitemap.xml quick-fetch for the current host

## ByteBandit
Everything in Advance, plus tools aimed at authorized security testing.
All 9 planned tools are now implemented.

- ✅ SSH terminal (login + command execution against a server you control)
- ✅ Host-key pinning for the SSH terminal — trust-on-first-use, pins a
  fingerprint via KnownHostsStore, refuses outright on mismatch (no
  override prompt - use "Forget Saved Host Key" to deliberately re-pin)
- ✅ WHOIS lookup — two-step raw socket query (whois.iana.org referral,
  then the actual registry server), port 43, plain text protocol
- ✅ DNS record lookup (A/AAAA/CNAME/MX/NS/TXT) — via Google's
  DNS-over-HTTPS JSON API rather than a hand-rolled binary DNS client
- ✅ Favicon hash lookup — Shodan-style mmh3 hash (hand-rolled
  MurmurHash3 x86_32, best-effort/unverified against a reference vector)
  plus a standard SHA-256 shown alongside as a reliable fallback
- ✅ Port scanner — TCP connect scan against ~29 common ports, gated
  behind a mandatory "I'm authorized to test this host" checkbox that
  must be checked before Scan is enabled
- ✅ Directory/file buster — lightweight gobuster-style path fuzzer, not a
  reimplementation of gobuster itself. Built-in ~170-entry wordlist (a few
  KB) or a pasted custom list, hard-capped at 2000 total requests (after
  extensions are factored in) to stay phone-appropriate rather than
  SecLists-scale. 8 concurrent requests, short timeouts, response bodies
  never read into memory - only the status code is touched. Same
  authorization checkbox gate as the port scanner
- ✅ GraphQL introspection helper — sends the standard introspection query,
  shows the raw schema JSON pretty-printed (no schema tree browser)
- ✅ TOTP generator — RFC 6238, HMAC-SHA1, 30s/6-digit, live-updating.
  Runs entirely locally, secret never leaves the device
- ✅ HAR export — request log → `.har` file in Downloads/Blackhole. url/
  method/timestamp are real; status/response fields are honest
  placeholders (0/empty) since the underlying log has no response data
  to report - see HarExport.kt's doc comment
- ✅ "Case file" export — zip of the request log (as HAR) + a manifest
  into Downloads/Blackhole. Does NOT auto-include Postman history (not
  persisted anywhere yet) or screenshots (saved separately, not tracked
  in a shared per-session store)

## Cross-cutting (not tied to one mode)
- Proxy indicator — show when `ProxyManager`/Tor/Burp routing is active in
  the same top bar as the mode badge, so it's never silently forgotten
- Route Postman requests, downloads, the SSH terminal, and the newer
  recon tools (WHOIS/DNS/port scanner/etc.) through `ProxyManager` when
  it's enabled (currently all of them connect directly)
- Downloads screen — list what's in Downloads/Blackhole, tap to share/open,
  swipe to delete (the underlying `DownloadManager` API already supports this)
- QR code generator (share a URL/text as QR) and scanner (open camera, jump
  to scanned URL)
- Password/passphrase generator with configurable entropy

---

## Notes on scope
A few of the ByteBandit-tier tools (port scanner, in particular) are
legitimate recon utilities but also the kind of feature worth keeping
visibly labeled as "for systems you're authorized to test" in the UI
itself — not just something to keep in mind while building it. The port
scanner's Scan button is hard-gated behind a checkbox for exactly this
reason.

Several tools in this batch make their own direct network connections
(HTTP, raw sockets, DoH) rather than going through `SecureWebView`'s
policy layer (Strict/CTF enforcement, ad blocking) or `ProxyManager`. This
is called out per-tool above and in each file's doc comment - it's a
known, consistent trade-off across the whole recon/testing toolset, not
an oversight in any one of them.
