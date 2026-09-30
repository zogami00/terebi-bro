# Terebi Bro

A self-contained Android TV fullscreen browser that is controlled from any
normal web browser on the same local network. No cloud service, no separate
controller app, no user account, no Google Play Services.

The TV app runs a fullscreen WebView plus an embedded HTTP/WebSocket control
server and advertises itself over mDNS. A phone, tablet or laptop on the same
LAN opens the bundled controller page and drives the TV browser.

## What it does

- Fullscreen immersive browser on Android TV (Android 11 / API 30+).
- Persistent configurable Home URL, keep-screen-awake and fullscreen settings.
- Embedded control server on a configurable port (default **8765**).
- Bundled, responsive web controller (no CDN, no external assets).
- Pairing PIN + per-device bearer tokens, CSRF header, Host/Origin checks.
- Live state over WebSocket (current URL, title, loading, network, uptime).
- Remote D-pad, navigation, browser management and settings.
- mDNS advertisement as `<deviceName>.local`, with an IP fallback.
- Automatic retry screen when the page is unavailable.
- WebView renderer crash recovery.

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
| compileSdk / targetSdk / minSdk | 36 / 36 / 30 |

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
one paired phone cannot kick out the others.

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

**Not verified on a device.** The project was validated only by building and by
JVM unit tests:

- `assembleDebug` builds and produces `app-debug.apk`.
- `testDebugUnitTest` passes: 86 JVM tests (PIN/token/auth logic, token registry
  (including revocation persistence and corrupt-blob recovery), rate limiter
  (including bucket pruning), Host/Origin matching, the per-peer connection
  limiter, the bound-port write-back rule, the Home-URL comparison used by Back
  handling, strict JSON parsing, request-body framing rules, the mDNS name
  sanitiser, the WebSocket frame-size cap, URL validation and subnet
  membership).
- `lintDebug` runs (0 errors).

Runtime behaviour — launcher visibility on a TV, immersive mode, D-pad focus,
WebSocket reachability, mDNS resolution, pairing against a real phone — has
**not** been exercised on hardware or an emulator, because none was available.
