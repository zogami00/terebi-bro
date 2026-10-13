# V1 scope

What the first release ships, and what was intentionally left out.

## Shipped in V1

**Browser**

- **Android 11+ (API 30) is the spec target**; the app also supports
  **Android 9 / 10 (API 28–29)** as a deliberate compatibility fallback (see
  [Deviation: minimum Android version](#deviation-minimum-android-version)).
- Fullscreen immersive WebView using the system provider.
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
  `/api/dpad`, `/api/settings/device-name`, `/api/settings/port`,
  `/api/settings/networks`, `/api/settings/mqtt`.
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
  reachable again from the on-device menu (remote MENU / INFO → **Settings**).
- Device diagnostics (manufacturer, model, Android/SDK, app version, WebView
  provider/version, IP, port, uptime) on the TV and via `/api/device`.

## Deviation: minimum Android version

The spec (`docs/implementation-plan.md` §3) sets **Android 11 / API 30** as the
minimum, with a recommended `minSdk = 30`. V1 ships `minSdk = 28` instead so it
can be installed on older TV hardware (Android 9 / API 28) that cannot be
upgraded to Android 11. The `INSTALL_FAILED_OLDER_SDK` rejection of a
`minSdk = 30` APK on an API 28 device is the concrete reason for the change.

The only source change this required is the immersive-fullscreen path. API 30
introduced `Window.setDecorFitsSystemWindows` and `WindowInsetsController`,
which do not exist below API 30, so the hide/show logic is gated on
`Build.VERSION.SDK_INT >= Build.VERSION_CODES.R`. On API 28–29 it uses the
legacy `decorView.systemUiVisibility` flags (the only mechanism available
there). API 30+ behaviour is byte-for-byte unchanged; `compileSdk` and
`targetSdk` remain 36.

The practical cost on an API 28–29 device: its system WebView is almost
certainly older than Chromium 100, so modern CSS may render as unstyled HTML.
The advisory WebView warning fires on such a device — that is intended, and it
never blocks the browser.

## Deviation: extra trusted client networks

The spec (§19) requires "local-network binding only": the controller is
reachable only from the TV's own subnet. V1 extends this with an **opt-in,
default-empty** list of additional trusted IPv4 networks (`extraClientNetworks`,
managed from the controller under **Settings** and via
`GET`/`POST /api/settings/networks`).

**Why.** A TV and its controller are not always on the same subnet — a TV at
`10.10.30.10` and a PC at `10.10.1.10` cannot reach each other under the strict
policy, so the user cannot even open the controller to configure anything. The
list makes the controller reachable from such a network (for example an admin
VLAN).

**The trade-off, stated plainly.** Reachability changes from **L2-adjacent** to
**routed**: any host that can route to the TV from within a trusted range is
admitted, not only the devices on the TV's own broadcast domain. An admitted
peer can reach `/api/pair`, `/api/info` and the WebSocket handshake. The
pairing PIN, the bearer token, the CSRF header, the Host/Origin checks, the
per-token/per-peer rate limits and the connection caps all still apply, so an
admitted-but-unpaired device cannot control the TV. But it **can drive the
global PIN lockout**: five failed `/api/pair` attempts lock pairing for
everyone, escalating on repeat to 15 minutes — a pairing denial of service from
anywhere in the trusted range. Keep the setting as narrow as the layout
actually requires. The blast radius is bounded deliberately:

- Only **non-globally-routable** ranges are accepted: RFC1918 (`10/8`,
  `172.16/12`, `192.168/16`), CGNAT (`100.64/10`) and link-local (`169.254/16`).
  A public network such as `1.0.0.0/8` is refused. A prefix broader than **/8**
  is refused, and input must be **canonical**: host bits set is rejected with
  the correct form named (`10.10.1.10/24` → `10.10.1.0/24`) rather than
  silently widened. The list is capped at **16** entries.
- The setting is **empty by default**, so the shipped behaviour is identical to
  "local-network binding only".
- Because the blocked subnet cannot reach the controller to configure it, the TV
  setup overlay lists recently-blocked client networks (in memory only, never
  persisted or logged) with a one-press allow of the containing /24.

## Extension: on-device menu (§15) and Enter URL (§16)

Spec §15 (a native long-press-Back menu) and §16 (Enter URL from the TV) were
originally deferred; they are now implemented.

- A **long press of Back** (hold ≥ 700 ms, where the hold is observable:
  **API ≤ 32**) or the remote's **MENU / INFO** key opens an on-device menu with
  **Home**, **Reload**, **Refresh WebView**, **Enter URL**, **Settings**,
  **Device information** and **Exit App**. **Back closes the menu** without
  acting on any item, and the menu does not trap focus.
- **Enter URL** opens a dialog (text field + **Open** / **Open + Set as Home** /
  **Cancel**) usable with both a D-pad/keyboard and touch. It validates through
  the existing `UrlValidator` (a missing scheme is upgraded to `https://`, a
  non-http(s) scheme is refused with an inline error).
- This **changes existing behaviour**: long-press Back used to **exit**; it now
  opens the menu, and exiting moved into the menu (**Exit App**). The **Exit
  App** button on the setup page is kept. MENU/INFO used to toggle the setup
  overlay directly; it now opens the menu, and the setup overlay is the
  **Settings** menu item.
- The Back ladder gains two rungs at the front (close the menu, close the
  device-information overlay); the existing ladder (close overlay → close
  fullscreen → history → home → setup page) is otherwise unchanged.

## Extension: MQTT control

The spec has no MQTT surface; this is a new, **off-by-default** extension.
When enabled, the TV publishes a retained `availability` (`online`/`offline`,
with a retained last will) and a retained `status` (the same state object as the
REST/WebSocket state, built from the one state-change path), subscribes to
`command` at QoS 1 and publishes `command/result`. Actions: `open`, `set-home`,
`home`, `back`, `forward`, `reload`, `stop`, `restart-webview`, `clear-cache`,
`clear-site-data`. TLS and username/password are supported.

**Auth model, stated plainly.** Broker access **is** control: there is no
controller-token check on MQTT commands, so anyone who can publish to the
command topic can drive the tablet. The broker credentials (and its topic ACLs)
are the only gate. The feature defaults to off for exactly this reason.

The MQTT client (Eclipse Paho, plain Java — no Android service, no Netty)
reconnects with exponential backoff capped at 60 s, resubscribes and
re-publishes the retained status on every reconnect, uses a stable
device-derived client id, never logs the password or the username, never places
a secret in a payload, and runs entirely off the UI thread. An unreachable
broker leaves the control server and the WebView unaffected.

Two additive REST routes back it: `GET`/`POST /api/settings/mqtt` (auth), and a
read-only `mqttConnected` in `GET /api/device`. A `POST` is validated as a whole
— a bad value is `400 bad_request` naming the field and nothing is written. The
password is never returned by a `GET` (a boolean `mqttPasswordSet` is).

## Deliberately deferred

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

Compiled, JVM-unit-tested (`190` tests), linted (`0` errors) and built as a
signed release.

**Verified end-to-end by the maintainer on the reference Android 9 (API 28)
device using the shipped `v0.2.0` release APK**, installed from the published
GitHub release: the Back ladder, long-press Back opening the on-device menu, the
Exit App button,
D-pad focus traversal inside a real site, controller pairing from a second
device with the 6-digit PIN, live WebSocket status updates, rendering of a real
Home URL (Chromium 138), the controller's on-screen D-pad driving TV focus, the
browser management actions (Restart WebView, Clear Cache, Clear Site Data), and
reachability of the controller over its `<device-name>.local` mDNS address. The
release APK installs, launches, binds the control server, advertises mDNS,
applies the legacy immersive fallback (`dumpsys window` reports the activity
window's `mSystemUiVisibility=0x1706`, the pre-API-30 flag set), and enforces
its release-only network policy (loopback refused, LAN served).

What this does not cover: there is **no CI and no automated device test suite**,
and this is a maintainer-run pass on **one API 28 unit**, not exhaustive
coverage. **API 30+ behaviour has been exercised only on an emulator (Android
14 / API 34), not on physical hardware**; Android 11+ real hardware remains
unverified.
