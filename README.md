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
- Live state over WebSocket (current URL, title, loading, network, uptime).
- Remote D-pad, navigation, browser management and settings.
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

Press the remote's **MENU** (or **INFO**) button to toggle the overlay.
**Revoke all devices** is TV-only: it is deliberately not exposed over HTTP so
one paired phone cannot kick out the others. The overlay is scrollable, so on a
short display (e.g. a 720p TV) every button — including **Exit App** — stays
reachable with the D-pad.

### Back behaviour

Back navigates. It walks a fixed ladder and every rung performs a real action —
Back is always consumed and never exits the app by accident:

1. info (setup) overlay open → close it
2. error overlay open → close it
3. HTML5 fullscreen video → exit the video
4. WebView has history → navigate back
5. not on the home page → load the home page
6. **at the true root → open the setup / pairing page**

Exiting the kiosk is therefore always deliberate, never the side effect of one
Back press, and there are two explicit ways to do it:

- **Long-press Back** (hold ≥ 700 ms) — **API ≤ 32 only**.
- **Exit App** on the setup page — **every API**. This is the exit path on
  API 33+.

Why the asymmetry: on **API 33+** the system is expected to route Back to the
`OnBackInvokedCallback` rather than to `dispatchKeyEvent`, so the DOWN→UP hold
duration would not be observable and long-press-to-exit would not be possible.
That framework behaviour is reasoned from the platform's Back-dispatch design,
not verified here on an API 33+ device, so the code does not rely on
`dispatchKeyEvent` seeing the key on API 33+ — long-press-to-exit is offered on
**API ≤ 32 only**, and the Exit App button is the universal path. On **API ≤ 32**
the activity sees the key event and measures the hold from the event's own
gesture start (`downTime`, which auto-repeat events do not shift, unlike
`eventTime`), so it also works on keyboards and repeating remotes; some TV
remotes never send repeats at all, so the duration is measured directly rather
than by counting repeats. On both paths the callback/consumed event runs the same
pure ladder (`BackOrder`), so Back at the root opens the setup page everywhere.
The `android:enableOnBackInvokedCallback="true"` attribute is required for the
callback to be delivered on API 33–35 (on API 36 it defaults to true; below
API 33 it is ignored).

`700 ms` is a reasoned default for the long-press threshold, not a value measured
on hardware, and the Back ladder and the hold timing have not been verified on a
device or emulator (see [Verification status](#verification-status)).

## Testing the controller on an emulator

The control server is reachable only from the TV's own LAN subnet, and an
Android emulator is NAT'd: the host cannot reach the emulator's LAN address
directly. A forward arrives at the device as a **loopback** connection, which
the LAN-only policy rejects. Debug builds therefore also bind the wildcard
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
  in the TV's subnet; the TV's own address is rejected too.
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

Validated by building, by JVM unit tests, and by an install/launch on a real
Android 9 (API 28) device:

- `assembleDebug` builds and produces `app-debug.apk`.
- `testDebugUnitTest` passes: 116 JVM tests (PIN/token/auth logic, token registry
  (including revocation persistence and corrupt-blob recovery), rate limiter
  (including bucket pruning), Host/Origin matching, the per-peer connection
  limiter, the bound-port write-back rule, the Back-behaviour ladder (including
  the root → setup-page rung) and the Home-URL comparison used by Back handling,
  the long-press hold-decision helper (threshold boundaries, a missing gesture
  start and a malformed negative delta), strict JSON parsing, request-body
  framing rules, the mDNS name
  sanitiser, the WebSocket frame-size cap, URL validation, subnet membership, the
  debug-only loopback/localhost peer & Host policy and the listener bind-host
  choice, and the advisory WebView-version check with its unknown-input
  safety).
- `lintDebug` runs (0 errors, `NewApi`-clean after lowering `minSdk`).
- `assembleRelease` builds and produces a signed `app-release.apk`.

**Partially verified on a device (Android 9 / API 28).** The debug APK was
installed and launched on a real Android 9 (API 28) device:

- `adb install -r app-debug.apk` **succeeds**; the same APK built with
  `minSdk = 30` was rejected with `INSTALL_FAILED_OLDER_SDK`.
- The activity displays, the setup / pairing overlay renders, the embedded
  control server binds, and mDNS advertises.
- The legacy immersive fallback is applied: `dumpsys window` reports the
  activity window's `mSystemUiVisibility=0x1706`, exactly the pre-API-30 flag
  set the fallback assigns (`LAYOUT_STABLE | LAYOUT_HIDE_NAVIGATION |
  LAYOUT_FULLSCREEN | HIDE_NAVIGATION | FULLSCREEN | IMMERSIVE_STICKY`).
- The advisory WebView warning fires, as expected on this hardware
  (`WebView v66 — out of date`).

The rest — an actual browser page rendered against a real Home URL, D-pad
focus, controller pairing against a phone, WebSocket reachability over the
LAN, and API 30+ runtime behaviour — has **not** been exercised on hardware or
an emulator.
