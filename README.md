# Terebi Bro

A self-contained Android TV fullscreen browser that is controlled from any
normal web browser on the same local network. No cloud service, no separate
controller app, no user account, no Google Play Services.

The TV app runs a fullscreen WebView plus an embedded HTTP/WebSocket control
server and advertises itself over mDNS. A phone, tablet or laptop on the same
LAN opens the bundled controller page and drives the TV browser.

## What it does

- Fullscreen immersive browser on Android TV (spec target Android 11 / API 30+;
  API 28–29 is supported as a compatibility fallback — see
  [Supported Android versions](#supported-android-versions)).
- Persistent configurable Home URL, keep-screen-awake and fullscreen settings.
- Embedded control server on a configurable port (default **8765**).
- Bundled, responsive web controller (no CDN, no external assets).
- Pairing PIN + per-device bearer tokens, CSRF header, Host/Origin checks.
- Optional extra trusted client networks, so a controller on a different subnet
  (for example an admin VLAN) can reach the pairing prompt.
- Live state over WebSocket (current URL, title, loading, network, uptime).
- Remote D-pad, navigation, browser management and settings.
- On-device menu (long-press Back, or the remote's MENU/INFO key) with Home,
  Reload, Refresh WebView, **Enter URL**, Settings, Device information and
  Exit App — so the tablet can be driven without a second device.
- Optional **MQTT control** (off by default): the TV can be driven from any MQTT
  broker. See [MQTT control](#mqtt-control).
- mDNS advertisement as `<deviceName>.local`, with an IP fallback.
- Automatic retry screen when the page is unavailable.
- WebView renderer crash recovery.

## Supported Android versions

The specification targets **Android 11 or newer (API 30)**
(`docs/implementation-plan.md` §3). Terebi Bro is built with `minSdk = 28`
and also runs on **Android 9 (API 28)** and **Android 10 (API 29)** as a
deliberate compatibility fallback for older TV hardware. This is a documented
deviation from the spec's Android 11 minimum (see `docs/v1-scope.md`).

What the fallback costs on an API 28–29 device:

- **Legacy immersive fullscreen.** Below API 30 there is no
  `WindowInsetsController`, so the app hides and shows the system bars with
  the pre-API-30 `decorView.systemUiVisibility` flags instead. The on-screen
  result is the same fullscreen picture, but it is the older, less capable
  mechanism. API 30+ behaviour is unchanged.
- **Old WebView.** These devices almost always ship an **Android System
  WebView** far older than Chromium 100, so modern CSS can render as unstyled
  HTML. The app's WebView version warning then fires on the TV overlay and in
  the controller — that is correct and never blocks anything (see
  [WebView version warning](#webview-version-warning)).

`compileSdk` and `targetSdk` remain 36; only `minSdk` was lowered.

### Legacy Android support

API 28 (Android 9) and API 29 (Android 10) are a **documented compatibility
fallback**, not the target: the app is specified for Android 11+ (API 30), and
`minSdk = 28` is a deliberate, documented deviation. On these older devices the
system WebView — not the app — is almost always the limiting factor. See
[`docs/legacy-android-9-support.md`](docs/legacy-android-9-support.md) for the
full runbook: diagnosing an old WebView and, where the ROM allows it,
whitelisting a newer provider.

## Download and install

**Download the latest release APK:**

> **<https://github.com/zogami00/terebi-bro/releases/latest>**

The asset is named **`app-release.apk`**.

Install or upgrade it from a machine with `adb`, on the same network and with the
TV reachable over ADB:

```powershell
adb install -r app-release.apk
```

The release APK is signed with a **dedicated release key**, which is different
from the key Android uses for local debug builds. Android refuses to replace an
installed app with one signed by a different key, so if a **debug** build of
Terebi Bro is already installed, uninstall it first:

```powershell
adb uninstall com.terebibro.tv
```

Upgrades from one release APK to a newer release APK work normally (`-r`).

## Building a signed release APK yourself

With the SDK and signing properties in place (see
[Build prerequisites](#build-prerequisites) and
[Release signing](#release-signing)):

```powershell
.\gradlew.bat assembleRelease
```

The signed APK is written to:

```text
app\build\outputs\apk\release\app-release.apk
```

## Release signing

The release APK is signed with a dedicated keystore that lives **outside this
repository** (default location
`%USERPROFILE%\.android\terebi-bro\terebi-bro-release.jks`). It is never
committed; `.gitignore` also blocks `*.jks`, `*.keystore` and `*.p12`, and the
signing secrets are held in `local.properties` (gitignored) or injected through
environment variables (`TEREBI_STORE_FILE`, `TEREBI_STORE_PASSWORD`,
`TEREBI_KEY_ALIAS`, `TEREBI_KEY_PASSWORD`).

If any of those four values is missing, the release build still configures and
succeeds but produces an **unsigned** APK
(`app-release-unsigned.apk`), so a fresh clone never breaks.

> **Back up the keystore and its password.** Losing them means no further
> updates can be installed over existing copies: Android only accepts an update
> signed with the same key. Keep a copy somewhere safe and offline — for a
> personal project, losing the release key is effectively losing the app on
> every TV it is installed on.

No secret value belongs in this README or anywhere else in the repository.

## Build prerequisites

- **JDK 21** (Temurin 21 recommended). The build targets Java/Kotlin bytecode 17
  but runs on the JDK 21 toolchain; no Java toolchain download is performed.
- **Android SDK** with:
  - platform `android-36`
  - build-tools `36.0.0`
  - platform-tools
- `local.properties` pointing at the SDK:

  ```properties
  sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
  ```

- No Gradle installation is required; the checked-in wrapper bootstraps
  Gradle **8.13**.

Toolchain versions used for this build:

| Component | Version |
| --- | --- |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.1.21 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 28 |

## Build and test

Set `JAVA_HOME` first, then from the repository root (Windows / PowerShell):

```powershell
$env:JAVA_HOME="C:\Users\nurfa\AppData\Local\Android\jdk21\jdk-21.0.12.1+1"
$env:ANDROID_HOME="C:\Users\nurfa\AppData\Local\Android\Sdk"

.\gradlew.bat clean assembleDebug --stacktrace
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

The APK is written to:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Install it on the TV with:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Defaults

| Setting | Default |
| --- | --- |
| Controller port | 8765 |
| Device name / mDNS host | `terebi-tv` (`terebi-tv.local`) |
| Home URL | `https://example.com` |
| Fullscreen | on |
| Keep screen awake | on |
| Automatic retry | on |
| Retry interval | 10 s |
| Trusted client networks | none (own subnet only) |
| MQTT | **off** |
| MQTT broker port | 1883 |
| MQTT TLS | off (plain `tcp://`) |
| MQTT base topic | `terebibro/<deviceName>` |

Changing the controller port persists the new value only after the bind is
probed; an unavailable port is rejected with `409 port_unavailable` and the old
listener keeps running. If a configured port fails at bind time the server falls
back to the default (8765) rather than stopping, and the port it actually bound
is written back to the stored configuration, so the Host allowlist, the mDNS
SRV record and the URL shown on the TV always point at the address that is
serving. If the very first bind fails on every candidate and there is no
previous listener to restore, the server retries the bind three times (two
seconds apart) before giving up. A controller that changes the port is
navigated to the new origin after the rebind, carrying its token in the URL
fragment (which is never sent to the server, and is moved into `localStorage`
and stripped on arrival).

## WebView version warning

The TV browser renders with the device's **system WebView** (its bundled
Chromium), which can be years out of date on a TV that has never been updated.
The Android 11 TV emulator's System WebView, for example, is Chromium
**90.0.4430.91** (April 2021). Modern CSS frameworks rely on features that
landed in Chromium **100** (March 2022) — cascade layers, `:has()`,
`color-mix()` and container queries — so on an older WebView a modern site
silently renders as **unstyled HTML**, with no other visible symptom.

Terebi Bro therefore surfaces an advisory warning when the installed WebView is
below Chromium 100:

- `GET /api/device` reports `webViewOutdated` (boolean, next to
  `webViewVersion`), and the controller shows a visible warning in its
  **Device** panel.
- The TV's setup / pairing overlay shows `WebView v<major> — out of date`.

The check is **advisory only** and never blocks anything: the browser loads,
navigates and runs exactly as before with an old WebView. A version that cannot
be parsed (missing, blank or garbage) is never treated as outdated, so an
unknown WebView never produces a false warning. To clear it, update **Android
System WebView** (Play Store → search *Android System WebView* → Update) or
install the device's system update.

## Pairing flow

1. On first launch the TV shows a setup overlay with:
   - `Controller: http://<deviceName>.local:<port>`
   - `IP:         http://<ip>:<port>`
   - a 6-digit pairing PIN
   - a **Start Browser** button
2. Open either address in a browser on the same LAN.
3. Enter the PIN (and a device name). The controller receives a bearer token
   that it stores in `localStorage["terebi.token"]`.
4. Press **Start Browser** on the TV (or it is skipped on later launches).

The PIN lives for 120 seconds and is single use. A "Pair device" action on the
TV issues a fresh PIN at any time.

### Reaching the setup overlay with the TV remote

Press the remote's **MENU** (or **INFO**) button to open the on-device menu, then
choose **Settings** to open the setup / pairing overlay. (MENU/INFO used to
toggle the overlay directly; it now opens the menu, and the overlay is a menu
item.) **Revoke all devices** is TV-only: it is deliberately not exposed over
HTTP so one paired phone cannot kick out the others. The overlay and the menu
are both scrollable, so on a short display (e.g. a 720p TV) every button —
including **Exit App** — stays reachable with the D-pad.

## On-device menu and Enter URL

The tablet can be driven without any phone, from the remote or by touch. Open
the on-device **menu** with:

- a **long press of Back** (hold ≥ 700 ms) — where the hold is observable, i.e.
  **API ≤ 32** (the same `BackOrder.isLongPress` path the old long-press-to-exit
  used), or
- the remote's **MENU / INFO** button — on every API.

The menu items are **Home**, **Reload**, **Refresh WebView** (the existing
WebView restart), **Enter URL**, **Settings**, **Device information** and
**Exit App**. **Back closes the menu** without acting on any item, and the menu
never traps focus — a short Back press runs the normal Back ladder from wherever
focus is.

**Enter URL** opens a dialog with a text field and **Open**,
**Open + Set as Home** and **Cancel**. It is fully usable with both a
D-pad/keyboard and touch. The value goes through the same `UrlValidator` as the
REST path: a missing scheme is upgraded to `https://`, and a non-http(s) scheme
(`javascript:`, `data:`, `file:`, …) is refused with an inline error. On error
the dialog stays open so the value can be corrected; on success it loads and,
for **Open + Set as Home**, persists the Home URL.

### Back behaviour

Back navigates. It walks a fixed ladder and every rung performs a real action —
Back is always consumed and never exits the app by accident:

1. on-device menu open → close it
2. device-information overlay open → close it
3. info (setup) overlay open → close it
4. error overlay open → close it
5. HTML5 fullscreen video → exit the video
6. WebView has history → navigate back
7. not on the home page → load the home page
8. **at the true root → open the setup / pairing page**

Exiting the kiosk is now a deliberate **menu item** (**Exit App**), and the
**Exit App** button on the setup page remains on every API. A long press of Back
no longer exits: it opens the menu.

Why the long press is API ≤ 32 only: on **API 33+** the system is expected to
route Back to the `OnBackInvokedCallback` rather than to `dispatchKeyEvent`, so
the DOWN→UP hold duration would not be observable. That framework behaviour is
reasoned from the platform's Back-dispatch design, not verified here on an
API 33+ device, so the code does not rely on `dispatchKeyEvent` seeing the key
there. MENU/INFO (and the menu itself, and the Exit App item in it) work on
every API. On **API ≤ 32** the activity sees the key event and measures the hold
from the event's own gesture start (`downTime`, which auto-repeat events do not
shift, unlike `eventTime`), so it also works on keyboards and repeating remotes;
some TV remotes never send repeats at all, so the duration is measured directly
rather than by counting repeats. On both paths the callback/consumed event runs
the same pure ladder (`BackOrder`), so Back at the root opens the setup page
everywhere. The `android:enableOnBackInvokedCallback="true"` attribute is
required for the callback to be delivered on API 33–35 (on API 36 it defaults to
true; below API 33 it is ignored).

`700 ms` is a reasoned default for the long-press threshold rather than a value
measured on hardware, though the Back ladder and the hold behaviour were
verified end-to-end by the maintainer on the reference Android 9 device (see
[Verification status](#verification-status)).

## Trusted networks (extra client networks)

By default the control server accepts connections **only from the TV's own
IPv4 subnet** (spec §19, "local-network binding only"). That is a problem when
the TV and the controller are on different subnets: a TV at `10.10.30.10` and a
PC at `10.10.1.10` cannot reach each other at all, so the user cannot even open
the controller to change a setting.

The **trusted networks** setting changes what "local" means, and it is worth
being explicit about that. It is a list of IPv4 CIDR ranges, **empty by
default**, in addition to the TV's own subnet:

- A peer is admitted to the controller when it is in the TV's own subnet **or**
  inside one of the configured networks. Reachability therefore changes from
  **L2-adjacent** (same broadcast domain only) to **routed**: any host that can
  route to the TV from within a trusted range is admitted, not just the devices
  sitting next to it.
- **The list only changes who may reach the pairing prompt.** It does not
  bypass the pairing PIN, the bearer token, the CSRF header, the Host/Origin
  checks, the rate limits or the connection caps. An unpaired device that
  reaches the page sees only the pairing screen and still needs the PIN.
- An admitted peer can reach `/api/pair`, `/api/info` and the WebSocket
  handshake. It cannot control the TV without pairing, but **it can drive the
  global PIN lockout**: five failed `/api/pair` attempts lock pairing for
  everyone, escalating on repeat to 15 minutes. That is a pairing denial of
  service from anywhere in the trusted range, so keep the setting as narrow as
  the layout actually requires.
- Only **non-globally-routable** ranges are accepted: RFC1918 (`10/8`,
  `172.16/12`, `192.168/16`), CGNAT (`100.64/10`) and link-local (`169.254/16`).
  Anything else (`1.0.0.0/8`, `8.8.8.0/24`, …) is refused with `only private,
  CGNAT or link-local ranges are allowed`.
- A prefix broader than **/8** is refused, and input must be **canonical**:
  host bits set is rejected with the correct form named, e.g. `10.10.1.10/24`
  → `host bits set; did you mean 10.10.1.0/24?`. It is never silently widened.
- The list holds at most **16** entries. A request with an invalid entry (or an
  17th one) is refused as `400 bad_request` and names the offending entries;
  nothing is silently dropped. Duplicates are collapsed.
- Values are stored as a normalised JSON array under `extraClientNetworks` and
  surfaced as `extraNetworks` in `GET /api/state`, the WebSocket `state`
  broadcast and `GET /api/device`.

It is managed from the controller's **Settings** panel (add and remove with
validation on the client and again on the server) via:

- `GET /api/settings/networks` — `{ "networks": ["10.10.1.0/24", …] }`
- `POST /api/settings/networks` — body `{ "networks": [ … ] }` replaces the list.

### The chicken-and-egg bootstrap (TV one-tap allow)

The setting is intentionally configured **from the controller**, but a controller
on a blocked subnet cannot reach the controller page to configure it. The TV
overlay closes that loop:

1. The PC at `10.10.1.10` tries to open the controller and gets
   `403 forbidden / remote`.
2. The TV records the rejected address **in memory only** (never persisted,
   never logged) and the setup / pairing overlay — reachable with the remote's
   **MENU** / **INFO** key — shows a **Blocked clients** list.
3. Pressing **Allow 10.10.1.0/24** adds the containing /24 in one press. Typing a
   CIDR with a TV remote is unworkable, which is exactly why this exists.
4. The overlay also lists the currently trusted networks, each removable.

The blocked list is bounded to the **5** most recent networks, de-duplicated by
the network the one-press allow would add (so several addresses in one /24 do
not render duplicate buttons), and cleared once an address is allowed. It is
never written to storage and the addresses are never written to the log; the
on-screen TV list is the only place an address appears. Loopback peers and the
TV's own address are never recorded, and only well-formed IPv4 peers are
listed.

## MQTT control

Terebi Bro can optionally expose itself on an MQTT broker, so the tablet can be
driven by any home-automation system. **It is OFF by default** and must be
enabled explicitly, because — as stated in plain terms below — **broker access
equals control**.

The setting is managed from the controller's **MQTT** panel and via
`GET`/`POST /api/settings/mqtt`; every field is editable at runtime and
persisted (`mqttEnabled`, `mqttHost`, `mqttPort` default **1883**, `mqttTls`
default **off**, `mqttUsername`, `mqttPassword`, `mqttBaseTopic` default
`terebibro/<mdnsName>`). TLS is supported: with `mqttTls` on, the client connects
over `ssl://`, otherwise `tcp://`. The MQTT client is a plain-Java client
(Eclipse Paho) — no Android service and no Netty.

### Auth model — the broker is the only gate

**Anyone who can publish to the command topic can drive the tablet.** There is
no controller-token check on MQTT commands: the broker's own authentication
(and its topic ACLs, if configured) is the *only* gate. A broker that lets a
client publish to `<base>/command` lets that client open URLs, change the home
page, restart the WebView and clear site data on the TV. Treat the MQTT
credentials — and the broker's topic permissions — with the same care as the
TV's pairing tokens, and keep MQTT disabled unless you actually want this.

The MQTT password and username are never logged, and no secret is ever placed in
a published payload.

### Topics and payloads (fixed contract)

`<base>` is `mqttBaseTopic`, defaulting to `terebibro/<mdnsName>`.

| Topic | Direction | QoS / retained | Payload |
| --- | --- | --- | --- |
| `<base>/availability` | publish | QoS 1, **retained** | `online` on connect. The **last will is `offline`, retained**, so a crash or a network loss reports `offline`; a clean shutdown also publishes `offline`. |
| `<base>/status` | publish | QoS 1, **retained** | JSON: the same state object as `GET /api/state` / the WebSocket `state` message (which already includes `ip`, `port`, `webViewVersion`, `appVersion` and `uptimeMs`). Republished on every change and again on each reconnect. |
| `<base>/command` | subscribe | QoS 1 | JSON `{"action":"...", ..., "id":"optional"}`. The payload is bounded to **4096 bytes**; a larger one is refused with `payload_too_large` before it is decoded. **Retained messages are ignored** (a retained command would otherwise re-run on every reconnect and reboot) — publish commands with `retain=false`. |
| `<base>/command/result` | publish | QoS 1, not retained | `{"id":<id>,"ok":true}` or `{"id":<id>,"ok":false,"error":{"code":"...","message":"..."}}` |

Actions: `open` (needs `url`), `set-home` (needs `url`), `home`, `back`,
`forward`, `reload`, `stop`, `restart-webview`, `clear-cache`,
`clear-site-data`. A URL is validated with the same `UrlValidator` the REST path
uses, so `javascript:`/`data:` and the like are refused (the result reports
`invalid_url`). An unknown action name is echoed back truncated to 32
characters, so the result payload stays bounded.

Example:

```jsonc
// publish to terebibro/living-room-tv/command
{"action":"open","url":"https://example.com/live","id":"42"}
// -> terebibro/living-room-tv/command/result
{"id":"42","ok":true}
```

### Robustness

- The client connects on start when enabled, and on a drop reconnects with
  **exponential backoff capped at 60 s**, resubscribing to `command` and
  re-publishing the retained `status` on every reconnect.
- The client id is **stable**, derived from the device (`terebibro-<device>`),
  so a reconnect is not seen as a new device. A clean session is used and the
  subscription is re-established on each connect.
- All MQTT work runs on its own daemon thread; commands are dispatched through
  the same main-thread path as the REST API. MQTT never blocks the UI.
- **If the broker is unreachable the app is entirely unaffected**: the control
  server, the WebView and mDNS keep working. The controller shows the
  connection state (connected / disconnected / error and the last error), and
  `GET /api/device` reports `mqttConnected`.

## Testing the controller on an emulator

The control server is reachable by default only from the TV's own LAN subnet,
and an Android emulator is NAT'd: the host cannot reach the emulator's LAN
address directly. A forward arrives at the device as a **loopback** connection,
which the LAN-only policy rejects. Debug builds therefore also bind the wildcard
address and accept loopback peers and the `localhost` / `127.0.0.1` Host forms.
Forward the port and open the controller from the host browser:

```powershell
adb forward tcp:8765 tcp:8765
```

```text
http://localhost:8765
```

The debug build's setup overlay also shows this URL as
`Debug (adb forward): http://localhost:8765`.

This works **only in debug builds** (`BuildConfig.DEBUG`). Release builds bind
to the TV's LAN IPv4 address only, reject loopback peers, and accept only the
`ip:port` and `<mdnsName>.local:port` Host forms, so the control server remains
LAN-subnet-only in production.

## Security model (in brief)

This is a LAN controller, not an internet-facing service. It speaks **cleartext
HTTP** and is bound to the TV's LAN IPv4 address only.

What it does protect against:

- Unpaired devices: every state-changing request and most reads require a
  bearer token. Tokens are 32 random bytes, stored only as a SHA-256 hash.
- PIN brute force: 6 digits, 120 s lifetime, single use, and after 5 failures
  pairing locks for 60 s, doubling to a 15 minute cap.
- Cross-site request forgery: an `X-Terebi-CSRF: 1` header is required on every
  `/api/*` request, and `Origin` is validated on POST and `/ws`. No CORS headers
  are ever emitted and `OPTIONS` is never handled.
- DNS rebinding: the `Host` header must equal the device `ip:port` or
  `<mdnsName>.local:port`. The device name is sanitised to a valid DNS label
  (lowercase `[a-z0-9-]`, ≤63 chars) before it is used for mDNS and Host checks.
- Off-subnet and loopback peers: the peer address is taken from the accepted
  socket (never from `X-Forwarded-For` / `X-Real-IP` / `Forwarded`) and must be
  in the TV's subnet; the TV's own address is rejected too. A peer outside that
  subnet is admitted only when it falls inside an explicitly configured
  [trusted network](#trusted-networks-extra-client-networks), and that admits it
  only to the pairing prompt — the PIN and token still gate control.
- Remote access abuse: rate limits of 20 req/s per token and 100 D-pad req/s,
  applied to REST requests and WebSocket messages alike.
- Connection exhaustion: concurrent handlers are capped globally (64) and per
  peer (8); a connection over a cap is closed immediately. On stop or rebind
  every live handler is closed, so established sockets cannot linger on the old
  listener.
- Slow clients: every WebSocket write (state, heartbeat/ping and close frames)
  is performed by that connection's own writer thread from a bounded queue, so
  one peer with a full TCP window cannot wedge the shared scheduler; the forced
  close tears the socket down directly instead of waiting on the frame lock.
- WebSocket hijacking: the token is not placed in the URL; a socket must
  authenticate within 5 seconds or is closed with code 4401. Messages are parsed
  with the same strict JSON rules as the REST API (no duplicate/unknown keys).
- Oversized WebSocket frames: the declared frame length (and the accumulated
  fragmented-message length) is capped at 64 KiB before NanoWSD allocates the
  payload, so an unauthenticated peer cannot exhaust memory.
- Request smuggling: every response returned before the POST body has been
  drained closes the connection, so leftover body bytes cannot be re-parsed as a
  new request. Chunked bodies and GETs that carry a body are always refused.

What it does **not** protect against:

- Other devices on the same trusted LAN that already hold a token.
- Anyone who can observe the network: **the controller traffic is not
  encrypted**. Treat the LAN as trusted.
- Malicious web pages the TV browser visits (the browser is the point of the
  app; there is no URL allowlist in V1 — see `docs/v1-scope.md`).
- Physical access to the TV.

Tokens never expire and are never rotated in place; revoke by unpairing or by
using **Revoke all devices** on the TV.

## Logging

All logging goes through `com.terebibro.tv.util.SafeLog`, which strips URL query
strings/fragments, masks token/PIN/secret/cookie/authorization key-value pairs,
and redacts bearer credentials. Tokens, their hashes, the PIN, `/api/pair`
bodies and WebSocket `auth` messages are never logged.

## Documentation

- `docs/implementation-plan.md` — the original implementation plan (verbatim).
- `docs/v1-scope.md` — what V1 ships and what was deliberately deferred.

## Verification status

Validated by building and by JVM unit tests:

- `assembleDebug` builds and produces `app-debug.apk`.
- `testDebugUnitTest` passes: 190 JVM tests (PIN/token/auth logic, token registry
  (including revocation persistence and corrupt-blob recovery), rate limiter
  (including bucket pruning), Host/Origin matching, the per-peer connection
  limiter, the bound-port write-back rule, the Back-behaviour ladder (including
  the root → setup-page rung and the new on-device-menu / device-information
  rungs) and the Home-URL comparison used by Back handling,
  the long-press hold-decision helper (threshold boundaries, a missing gesture
  start and a malformed negative delta), strict JSON parsing (including the
  opt-in string-array path and the new optional string/int accessors),
  request-body
  framing rules, the mDNS name
  sanitiser, the WebSocket frame-size cap, URL validation, subnet membership, the
  debug-only loopback/localhost peer & Host policy, the CIDR parser and list
  validation behind the trusted-networks setting (prefix bounds, the
  private/CGNAT/link-local range restriction, non-canonical input rejection,
  membership boundaries, de-duplication and the 16-entry cap), the
  bounded recently-blocked peer list (bound, de-duplication, recency, clear),
  the extra-network peer policy, the listener bind-host
  choice, the advisory WebView-version check with its unknown-input
  safety, and the MQTT pure pieces — topic building and device-name sanitising,
  the strict command parser (valid actions plus every failure mode: unknown
  action, missing `url`, invalid URL and malformed JSON), the status payload
  (built from the shared state object and carrying the required device fields),
  the settings validator (bounded host/port/topic, field-naming and no partial
  write) and the capped reconnect backoff).
- `lintDebug` runs (0 errors, `NewApi`-clean after lowering `minSdk`).
- `assembleRelease` builds and produces a signed `app-release.apk`.

**Verified end-to-end by the maintainer on the reference Android 9 (API 28)
device, using the shipped `v0.2.0` release build installed from the published
GitHub release.** Every path below passed:

- **Back ladder** — closes the overlays, steps through history, loads the home
  page, and at the root opens the setup page rather than exiting.
- **Long-press Back menu** — holding Back opens the on-device menu (the
  API ≤ 32 path).
- **Exit App button** — exits the app.
- **D-pad focus traversal** — the remote moves focus correctly inside a real
  site.
- **Controller pairing** — paired from another device using the 6-digit PIN.
- **Live WebSocket status** — the controller panel updates without a refresh.
- **Real page rendering** — a real Home URL renders correctly on Chromium 138.
- **Web D-pad buttons** — the controller's on-screen D-pad drives focus on the
  TV.
- **Browser management actions** — Restart WebView, Clear Cache, Clear Site
  Data.
- **mDNS `.local` access** — the controller was reached via the
  `<device-name>.local` address, not only the IP.

Additionally, and already recorded: the release APK installs on the device,
launches, binds the control server, advertises mDNS, applies immersive mode
(`dumpsys window` reports the activity window's `mSystemUiVisibility=0x1706`,
exactly the pre-API-30 flag set the fallback assigns, i.e. `LAYOUT_STABLE |
LAYOUT_HIDE_NAVIGATION | LAYOUT_FULLSCREEN | HIDE_NAVIGATION | FULLSCREEN |
IMMERSIVE_STICKY`), and enforces its release-only network policy (loopback
refused, LAN served). The advisory WebView warning fires, as expected on this
hardware (`WebView v66 — out of date`).

What this does **not** cover: there is **no CI and no automated device test
suite**, so this is a maintainer-run end-to-end pass on **one API 28 unit**, not
exhaustive coverage. The **API 30+ immersive path has been exercised only on an
emulator (Android 14 / API 34), never on physical hardware**, so Android 11+
real-hardware behaviour remains unverified.
