# V1 scope

What the first release ships, and what was intentionally left out.

## Shipped in V1

**Browser**

- Android 11+ (API 30), fullscreen immersive WebView using the system provider.
- Persistent, configurable Home URL.
- JavaScript, DOM storage, cookies; audio/video; HTML5 fullscreen video
  (custom view swapped into the root container).
- D-pad / ENTER / DPAD_CENTER forwarded to the WebView; remote Back walks
  history and is consumed otherwise.
- Keep-screen-awake and fullscreen toggles.
- WebView renderer crash recovery (destroy + recreate + reload).
- Network failure screen with auto-retry, **Retry Now** and **Home**.

**Embedded server**

- NanoHTTPD + NanoWSD control server bound to the LAN IPv4 address only
  (no IPv6 listener), rebinding on network change and stopping with no network.
- Bundled controller page at `/`, `/app.js`, `/app.css` (no CDN, CSP locked to
  `'self'`).
- REST surface: `/api/info`, `/api/pair`, `/api/unpair`, `/api/state`,
  `/api/device`, `/api/nav/open`, `/api/home`, `/api/nav/{home,back,forward,reload,stop}`,
  `/api/webview/{restart,clear-cache,clear-site-data}`, `/api/display`,
  `/api/dpad`, `/api/settings/device-name`, `/api/settings/port`.
- WebSocket at `/ws` with 250 ms-coalesced state broadcasts, heartbeat and
  in-band authentication.
- Debug-only (`BuildConfig.DEBUG`) emulator affordance: the server also binds
  the wildcard address and accepts loopback peers and the `localhost` /
  `127.0.0.1` Host forms, so `adb forward tcp:8765 tcp:8765` reaches a NAT'd
  emulator. Release builds never take this path and stay LAN-subnet-only.

**Discovery**

- mDNS advertisement of `_http._tcp` as `<deviceName>.local`.
- The IP fallback is always shown and never depends on mDNS.

**Security**

- Pairing PIN (6 digits, 120 s, single use) with global failure counting and
  escalating lockout (60 s doubling to a 15 min cap).
- 32-byte base64url bearer tokens stored only as SHA-256 hashes; constant-time
  lookup; never rotated in place.
- Ordered request checks: subnet peer → route/method → Host → Origin → CSRF →
  content type → content length → bearer token → rate limit → strict JSON.
- Strict JSON parsing via `android.util.JsonReader` (non-lenient, top-level
  object only, no unknown or duplicate keys).
- TV-only "Revoke all devices"; no HTTP route for it.

**TV-side**

- First-run setup overlay showing device name, both addresses and the PIN,
  reachable again with the remote's MENU / INFO button.
- Device diagnostics (manufacturer, model, Android/SDK, app version, WebView
  provider/version, IP, port, uptime) on the TV and via `/api/device`.

## Deliberately deferred

- **Native long-press-Back menu (§15)** and **Enter URL from TV (§16).** The
  TV-side browser menu and on-TV URL/keyboard entry are not implemented. The
  setup overlay is reachable via the remote's MENU / INFO key instead, and URL
  entry is done from the web controller.
- **URL allowlist / restricted mode (§20).** V1 runs in open mode only; the
  `allowArbitraryUrls` / `allowedDomains` settings are not implemented.
- **Boot auto-start (§8).** No `BOOT_COMPLETED` receiver / auto-start setting.
- **Phase 8 kiosk features.** Lock Task / device-owner mode, scheduled reload,
  brightness control, screenshots, bookmarks, QR code, config export/import,
  OTA/fleet integration.
- **Local log viewer (§25).** Application logging exists via `SafeLog`, but the
  controller has no View/Download/Clear Logs UI.
- **Administrator password (§19).** Only pairing PIN + tokens are implemented.
- **HTTP `OPTIONS` / CORS.** Intentionally never handled.

## Verification

Compiled and JVM-unit-tested only. No device or emulator was available, so
launcher visibility, immersive mode, D-pad behaviour, WebSocket reachability and
mDNS resolution are implemented but **not verified at runtime**.
