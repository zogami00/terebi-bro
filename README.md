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
  broker, and publishes **Home Assistant MQTT Discovery** so it appears as one
  device. See [MQTT control](#mqtt-control).
- Optional **media mode** (**off by default**): a signage loop beside the queue
  dashboard — local files and/or an HTTP playlist in a split layout, with the
  QMS page restyled into a call strip. The menu, the controller and MQTT can all
  switch between QMS only, media only and split. See
  [Media mode](#media-mode).
- **Idle split, call takeover.** While nothing is being called the screen shows
  media plus the queue strip; a detected call (a DOM change, or optionally the
  page's chime) takes the whole screen for a configurable dwell, then returns.
- **Local media plus a playlist**, never both at once: files under
  `/sdcard/Android/data/com.terebibro.tv/files/media/` (an `adb push` is
  enough), or an HTTP JSON playlist with still-image durations.
- **Upload from the controller**: authenticated, bounded and magic-byte checked;
  no USB ritual and no storage permission.
- **Signage language selector** for the patient-facing labels (Malay by
  default; English, Chinese and Tamil included, machine-translated).
- **MQTT/HA additions for media**: next/play/pause buttons, a mode sensor and a
  now-serving sensor on the same discovered device.
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

The asset is versioned, named **`terebi-bro-v<version>.apk`** — for example
**`terebi-bro-v0.6.1.apk`** — so a downloaded copy stays identifiable.

Install or upgrade it from a machine with `adb`, on the same network and with the
TV reachable over ADB:

```powershell
adb install -r terebi-bro-v0.6.1.apk
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
| HA MQTT discovery | on |
| HA discovery prefix | `homeassistant` |
| Media mode | **off** |
| Call dwell | 50 s |
| Band split (media / header / queue) | 71 % / 7 % / 22 % |
| Still-image duration | 10 s |
| Playlist refresh | 300 s |
| Call source | `dom` |
| Media volume | 100 % |
| Signage language | `ms` (Malay) |

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

## Controller page

The bundled controller is one self-hosted page (no CDN, no web fonts, no inline
script or style: `script-src` and `style-src` are `'self'`). A top bar shows the
device name, an online/offline pill and the current media mode, plus one amber
line when QMS detection fails. Controls live in five tabs (a bottom tab bar on a
phone, a top tab bar on wider screens), with tab state kept in `sessionStorage`:

| Tab | What it holds |
| --- | --- |
| **Remote** | Now calling and what is playing, Media mode (QMS only / Split / Media only) and its Enabled switch, the D-pad with Back, Play/Pause and Next, and Text input (`POST /api/text`) for the media page. |
| **Media** | Media URL (open now, open on start, default source), the Local folder (**Play from**: app storage or a folder picked on the TV's storage or a USB/SD drive; upload, list, preview, delete) and the Playlist. |
| **QMS** | QMS URL with the strip/number/location/time selectors, call source and QMS UI scale; the QMS view (open URL, Back, Forward, Home, Reload, Stop). |
| **Display** | Split layout (media band / header band / QMS strip, totalling 100 %), text sizes and UI language, QMS focus on call, image duration, media volume and image fill mode. |
| **Settings** | Device name, controller port, Home URL, Fullscreen and Keep screen on, trusted networks, WebView restart/cache/site data, MQTT, diagnostics and unpair. |

Each section saves only its own fields, so one invalid value never blocks an
unrelated section. Destructive actions (delete a file, clear site data, unpair)
ask for confirmation. Playback from the page uses the same controller methods
the MQTT actions do, through `POST /api/media/next`, `/api/media/play` and
`/api/media/pause`; with media mode off these return
`{"applied":false,"reason":"media_disabled"}`.

## Media mode

**Media mode is off by default.** With it off the app is V1 exactly: one
fullscreen WebView, the same Back ladder, the same overlays and the same MQTT
commands. Enable it from the controller's Remote tab (or
`POST /api/settings/media {"mediaModeEnabled": true}`) and the TV can show a signage
loop beside the queue dashboard.

The split layout is authored for a 1080p **dp** surface and the whole interface
scales down proportionally on a shorter one (617 dp renders at 57 %), so a phone
is not the intended display — a 1080p TV at density 1.0 shows it full size.

### Four modes

| Mode | What is on screen |
| --- | --- |
| `qms_only` | V1 exactly: the vendor page full screen. |
| `media_only` | The media band only, full screen — a media workspace, not a display mode to sit in. It holds for 30 minutes and then returns to `split`. |
| `split` | Media, header and queue strip (71 / 7 / 22 %). The automatic default once the dashboard has rendered. |
| `qms_focus` | The vendor page full screen for the duration of a call. This is the reducer's transient mode; it cannot be selected manually. |

`qms_only` may be held indefinitely (the queue stays visible), `media_only` for
30 minutes, and `split` drops any hold. A call arriving during a manual hold
still takes the screen and returns to the hold only if it has not expired. The
mode is switched from the on-device menu, the controller, MQTT `set-mode`, or
`POST /api/media/mode`.

**Boot rule.** The QMS asks for a code and a button press once a day before it
shows the dashboard, and a 238 px strip makes that form unusable. Media mode
therefore does **not** engage until the strip selector has matched at least once
since launch: a cold start stays `qms_only` full screen through the activation
form, and reaches `split` only once the dashboard is actually rendering. A
renderer crash or a WebView restart resets that history, because the reload
lands on the activation page again.

Installing the script against a page that is **already loading or loaded**
evaluates it once immediately, on both the document-start and fallback paths.
The document-start handler only reaches documents created after it is installed,
so without that immediate evaluation enabling media mode on a live page (or a
startup ordering where the load began first) would leave the strip unprobed
until a manual reload. The script's own install guard makes the second run
harmless, and its observer plus one-second re-probe cover a document that was
evaluated before its cards rendered. A WebView restart re-installs the script
before the new load, so it too needs no manual reload.

### The strip

The queue band is a restyle of the vendor page's own nodes, not a copy of its
text. `SEDANG DIPANGGIL` sits above the current call and `PANGGILAN TERDAHULU`
above the history cards, as CSS `::before` content — so a vendor re-render
cannot wipe them and they cannot contaminate the detection key. The labels are
translated by the signage language selector; the vendor's own number, location
and time are always shown verbatim. If the vendor markup changes and the
selectors stop matching, the strip says `PAPAN GILIRAN TIDAK TERSEDIA` rather
than showing a stale or invented number.

The two bands are pinned to the viewport rather than distributed through the
vendor's `main` column: the header is fixed at the top with the configured
header-band height, the queue band is fixed from the header's bottom to the
screen edge, and the card row fills it absolutely. The vendor's own padding and
`gap` therefore cannot push the queue band below the screen edge on the
reference Chromium 66 device, and the card type is derived from the band height
so it shrinks with the setting.

The call signal is the DOM change by default. `callSource` may be
`dom_or_audio` to also treat the page's chime as a call, or `audio` to let only
the chime take the screen (the DOM change still updates the number and location,
but no longer starts a takeover). An audio signal re-arms the dwell and takes the
screen exactly like a DOM call, but it **never invents a number** — with no DOM
call observed the strip keeps showing what it last showed, or the no-call label. A chime is not a contract: it is
best-effort on a WebView too old for document-start scripts, and a `<video>`
used as a sound source is indistinguishable from programme audio.

The QMS URL and the four detection selectors are set in the controller's QMS
tab, and default to the reference dashboard's structure
(see the settings table below). A blank QMS URL tracks the Home URL. Enabling
media mode, or saving the settings, on a page that is already loaded reads that
page immediately — a reload is not needed.

### Where media lives

Local files live in the app-private external folder:

```text
/sdcard/Android/data/com.terebibro.tv/files/media/
```

There is no runtime permission, no SAF picker and no USB ritual; `adb push` into
that folder works, and the folder is **rescanned on resume**, so a push while
the app is in the foreground appears without a restart. The currently playing
item is kept when it still exists. A push that does not change the folder's
modification time is invisible to a plain resume, so the Videos and images card's
**Reload list** button (or `POST /api/media/rescan`) forces a re-read while the
app stays in front. The button reports the queue size it saw; an empty
`Folder on the TV · 0 items` line next to the band's "media unavailable" notice
means the folder really is empty, not that the scan was stale.

### Uploading from the controller

`POST /api/media/upload` streams a file with `Content-Length` and the filename in
the `X-Terebi-Filename` header (never a query parameter). Writes go to a
`<hash>.part` file and are renamed atomically, so a partial upload is never
visible to the queue. The caps are fixed constants, not settings:

| Rule | Value |
| --- | --- |
| Per file | 256 MiB |
| Files | 200 |
| Total | 2 GiB |
| Free-space reserve | 256 MiB, checked before the write |
| Concurrency | 3 uploads |
| Rate | 6/min per token |

Magic bytes must match the extension. Accepted containers are MP4/M4V/MOV
(`ftyp` at offset 4), Matroska/WebM (the EBML header `1A 45 DF A3`), JPEG SOI
and the PNG signature — `.mp4`, `.m4v`, `.mov`, `.mkv`, `.webm`, `.jpg`,
`.jpeg` and `.png`. **That is the gate** — a JPEG renamed `.mp4` is refused
here, and so is an MP4 renamed `.mkv`: the container family must agree with the
extension, not just the playable kind. The
`MediaMetadataRetriever` check is **advisory only** and never refuses the upload:
the retriever cannot introspect every valid container on every device (the API 28
emulator rejects a good H.264/AAC MP4), and refusing a valid file is worse than
admitting one that later fails to play — a play failure is visible, recoverable
and advances the queue, while a silent rejection looks like the feature is
broken. The upload response carries `probe` (`readable`, `unreadable`, `error`);
anything but `readable` shows a warning in the controller, not an error. A file
that passes and still will not decode is reported as `mediaError` and skipped.

### HTTP playlist

A playlist is a JSON array. Ordering is the array order, `durationSec` applies to
stills and must be 3–120, and URLs must be `http`/`https`:

```json
[
  {"type": "image", "url": "https://example.com/promo.jpg", "durationSec": 10},
  {"type": "video", "url": "https://example.com/clip.mp4", "durationSec": 30}
]
```

The body is bounded to 256 KiB and 200 items, parsed strictly (top-level array,
known keys only, no duplicate keys) and refreshed every
`playlistRefreshSec` (default 300). A failed refresh keeps the last-good copy;
a successfully fetched but empty or wholly invalid playlist is treated as "this
playlist has nothing playable" and the local folder takes over. **Playlist or
local folder, never both**: the playlist wins only when it is enabled and has at
least one valid entry, and the two are never merged or interleaved. An ad-hoc
URL (`POST /api/media/open`) replaces the queue until another source is chosen.
An **`.m3u8` (HLS) URL** can be used directly in the playlist or in "Play from
URL": it is handed to the platform `MediaPlayer`, which handles HLS on this
range, and whether it actually plays depends on the stream's codecs.

### Playing a folder from the TV or a USB drive

The local source plays app storage (uploads) by default. **Media → Local folder →
Choose folder…** browses the TV's internal shared storage and every mounted USB
or SD volume, one level at a time (`POST /api/media/folders {"path": ""}` returns
the roots), and **Play this folder** saves it as `mediaLocalFolder`. Android TV
has no system folder picker, so the controller is the picker. The first browse
shows Android's "access photos and videos" prompt on the TV (`READ_MEDIA_VIDEO`
/ `READ_MEDIA_IMAGES`, or `READ_EXTERNAL_STORAGE` before Android 13). Only files
directly in the folder play. Plugging or unplugging a drive re-reads the folder;
while it is missing the local source is empty. Uploads always go to app storage.

### Web pages in the media band

The media band can also host a **web page** — a Jellyfin UI, a YouTube page,
any `http`/`https` site — as a fourth source beside the folder, the playlist and
an ad-hoc URL. It is opened from the controller's Media tab (**Open now**),
from the on-device menu (**Open web in media**), or with
`POST /api/media/open {"url": "...", "web": true}`. Omitted or `false` keeps the
native player path.

- It is a **second renderer** (a separate `WebView` from the QMS page), so it
  costs the memory of a second Chromium instance while it is alive. It is built
  lazily on first use and reused afterwards.
- It needs a **modern system WebView**. The QMS page's minimum (Chromium 66) is
  enough to load a page, but a modern Jellyfin or YouTube client will behave
  poorly on an old one.
- Autoplay **depends on the site**: the app asks for no user-gesture requirement,
  but a page can still refuse to start media on its own.
- A web page is a **single deliberate source, not a queue item**: the playlist
  parser rejects `type: "web"` entries, next/previous do nothing while a page is
  open, and the published `mediaSource` is `web` with a count of 0.
- The band's **pause contract reaches the page**: an overlay, `QMS_FOCUS`,
  `qms_only` or `POST /api/media/pause` (the MQTT action, the HA button and the
  controller's Play/Pause use it) pauses the page's media clock, and
  `mediaPaused` reflects what it is doing. A page that is hidden because another
  source took the band stays paused; it is not resumed on the next foregrounding.
- A page that **fails to load** (main-frame error or a dead renderer) is
  dropped: the error is published and the folder or playlist takes over, so
  clearing the web setting — or toggling media mode — is never needed to
  recover. A persisted startup page that the settings no longer select is
  dropped the same way.
- The URL is **masked before it leaves the app**. `mediaItem` in the state
  broadcast keeps only `scheme://host[:port]/path`; the query and fragment
  (where a Jellyfin or YouTube token lives) are stripped, and nothing logs the
  raw URL. `GET /api/settings/media` returns `mediaWebUrl` the same way, plus a
  `mediaWebUrlSet` boolean, so the stored URL itself never crosses the wire —
  the convention `/api/settings/mqtt` follows for `mqttPasswordSet`.
- The **D-pad only reaches the page in `media_only`**. In `split` the media
  WebView is not focusable, so the remote keeps driving the QMS page; switch to
  `media_only` to navigate the page. The decision is made once, in the pure
  `MediaKeyRoute.route(key, mode, webActive, overlayVisible)` predicate, and both
  the hardware remote and `POST /api/dpad` use it — the controller's on-screen
  D-pad used to call the QMS view directly and kept navigating the hidden QMS
  page. In `media_only` with a web page and no overlay the activity hands every
  `DPAD`/`ENTER` press and release straight to the page's `WebView` instead of
  relying on window focus, because a device was observed reporting focus on the
  page while the page still never received the key. The event is consumed even
  if the page ignores it, so it cannot fall through to the hidden QMS page; a
  guard refuses and logs once if a synthetic key would ever reach the QMS view
  in that state. `back` from `/api/dpad` still takes the normal `onRemoteBack()`
  ladder. The same routing keeps working while the page's own HTML5 fullscreen
  view covers the band: the keys still go to the page's `WebView` behind that
  view.
- The **QMS surface is genuinely gone in `media_only`**, not merely covered.
  `webview_container` is `GONE` and the QMS page itself is `GONE`, so it can
  neither take a key nor keep the Split band's height; the layout pass asserts
  that invariant every time it runs (a violation is repaired and logged, never
  thrown). The band's page re-applies its `MATCH_PARENT` `LayoutParams` on every
  mode change, and is re-focused after that pass (with a posted second request),
  because a page created while the container was the Split band otherwise keeps
  the old bounds and the old focus. A debug-only `media layout …` line records
  the mode, `webview_container` visibility, the page's visibility and height,
  and the focused view after each pass, which is how the fix is confirmed on the
  device.
- **HTML5 fullscreen is band-scoped.** A YouTube/Jellyfin fullscreen request is
  honoured by adding the page's own fullscreen view inside the media band
  (`media_container`), above the page, with a black background; the page is
  hidden while it shows. It is **never** routed through the QMS WebView's
  fullscreen path, which swaps into the whole screen. In `media_only` the band
  *is* the whole screen, so the result is real fullscreen; in `split` the view
  fills only the media band and the queue stays visible, which is intended. Back
  leaves the video before it leaves the workspace, and a `QMS_FOCUS` takeover
  (or another source taking the band) removes the fullscreen view first. A
  renderer crash clears the stored view and callback so a dead view is never
  left attached.
- **Typing needs an IME.** `windowSoftInputMode` is `adjustResize`, and the
  activity no longer re-applies immersive mode while the soft keyboard is up
  (API 30+ reads `WindowInsets.Type.ime()`; below 30 it treats a focused media
  page in `media_only` as the conservative case). The page is focusable and
  touches focus in `media_only`. The on-device menu also has **Show keyboard**
  (only in `media_only` with a web page open), which raises the IME with
  `SHOW_FORCED` and says so in the menu banner when the device has no IME at
  all. **On a television the reliable path is Jellyfin's Quick Connect, or a
  USB/Bluetooth keyboard** — a soft keyboard depends on the device having an IME
  installed and selectable in the first place.
- Cookies and DOM storage are enabled (defaults) so a Jellyfin login survives
  for the process lifetime. The media view has **no JavaScript bridge at all**;
  the QMS view exposes only `terebiQms`, the write-only signal sink. Non-`http(s)`
  navigation, SSL errors, permission requests and console logging are refused.
- The page can be the **persisted startup source**: `mediaWebUrl` stores the
  link in full (a Jellyfin URL may carry a token), `mediaWebEnabled` opens it on
  startup, and `mediaSourceDefault = web` makes it the default source. The
  controller applies that default once at startup and again when those settings
  change, but never over a source the operator chose during the session. A `web`
  default without a URL is rejected naming `mediaSourceDefault`. A blank
  `mediaWebUrl` in a `POST` **keeps the stored URL** (leave the controller field
  blank to keep it), so there is no clearing path: turn `mediaWebEnabled` off or
  move `mediaSourceDefault` away from `web` instead.

### Settings and defaults

Everything is editable at runtime and persisted; a `POST` is validated as a
whole, so one bad value writes nothing.

| Key | Default | Notes |
| --- | --- | --- |
| `mediaModeEnabled` | `false` | Master switch; off means V1. |
| `qmsUrl` | Home URL | `http`/`https` only; a blank value tracks the Home URL. |
| `qmsStripSelector` | `main > div:nth-child(1)` | The left column to restyle. |
| `qmsNumberSelector` | `div:first-child > span` | Call number. |
| `qmsLocationSelector` | `div:nth-child(2) > span:first-child` | Location/counter. |
| `qmsTimeSelector` | `div:nth-child(2) > span:nth-child(2)` | Time. |
| `callSource` | `dom` | `dom`, `dom_or_audio` or `audio`. |
| `dwellSec` | `50` | 20–180. |
| `mediaSplitPercent` / `headerBandPercent` / `stripHeightPercent` | `71` / `7` / `22` | Must sum to 100. |
| `imageDurationSec` | `10` | 3–120; applies to stills. |
| `mediaLocalEnabled` | `true` | The local folder as a source. |
| `playlistEnabled` / `playlistUrl` / `playlistRefreshSec` | `false` / empty / `300` | Off until a URL is configured. |
| `mediaWebUrl` | empty | The web page the media band can show; `http`/`https` only, stored in full (it may carry a token). Read back masked; a blank `POST` value keeps the stored one. |
| `mediaWebEnabled` | `false` | Open `mediaWebUrl` on startup instead of the folder/playlist. |
| `mediaSourceDefault` | `local` | `local`, `playlist` or `web`; `web` requires `mediaWebUrl`. |
| `volumePercent` | `100` | 0–100; a waiting room will usually want 0. |
| `uiLanguage` | `ms` | `ms`, `en`, `zh` or `ta` — patient-facing labels only. |
| `mediaFillMode` | `letterboxBlur` | `letterboxBlur`, `letterbox` or `cover` (the only mode that crops). |

A video is always letterboxed to its own aspect ratio inside the media band and
centred; `mediaFillMode` applies to stills only, so a clip is never stretched to
the band or cropped. The band's black background becomes the bars.

All pixel values are authored against a **1080p baseline** and multiplied at
runtime by `renderScale = screenHeightDp / 1080`, taken from the dp the device
reports (not its physical pixels). Native band heights, card slots and label
sizes use the same factor, and the injected strip stylesheet is built with it.
A density-scaled 4K panel reports 1080 dp, so the factor is `1.0` and nothing
changes; a device reporting native dp at 4K gets `2.0` and the same picture,
larger. There is no second 4K design.

**Limitations, stated plainly.** The media band plays the containers the
platform's `MediaPlayer` decodes: **MP4/M4V/MOV, Matroska/WebM, JPEG and PNG**
(API 28+). **HEVC and MKV still need Media3** (`androidx.media3`) — the MKV
*container* now plays, but an HEVC track inside it does, and an HEVC MP4 does
not; Media3 is out of scope for this band, and opening a web page does not change
that. There is no seeking. A chime played by a native app on the TV is invisible
to the WebView. The strip depends on the vendor page's structure: if it changes,
the strip degrades to "queue board unavailable" rather than guess. And nothing
in this section has been verified on a device yet — see
[Verification status](#verification-status).

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
**Exit App**. In `media_only` with a web page open it also shows **Show
keyboard**, which raises the device IME for that page (and reports in the menu
banner when the TV has no IME at all). **Back closes the menu** without acting on
any item, and the menu never traps focus — a short Back press runs the normal
Back ladder from wherever focus is.

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
`terebibro/<mdnsName>`, `mqttDiscoveryEnabled` default **true**,
`mqttDiscoveryPrefix` default **homeassistant**). TLS is supported: with
`mqttTls` on, the client connects
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
| `<base>/status` | publish | QoS 1, **retained** | JSON: the same state object as `GET /api/state` / the WebSocket `state` message (which already includes `ip`, `port`, `webViewVersion`, `appVersion` and `uptimeMs`), plus `discoveryTopics` (how many discovery configs the last publish attempt wrote successfully), `discoveryError` (a short, redacted reason for the last discovery publish failure, or `null`) and `statusError` (a short, redacted reason for the last retained `status` or `availability` publish failure, or `null`). Republished on every change and again on each reconnect. |
| `<base>/command` | subscribe | QoS 1 | JSON `{"action":"...", ..., "id":"optional"}`. The payload is bounded to **4096 bytes**; a larger one is refused with `payload_too_large` before it is decoded. **Retained messages are ignored** (a retained command would otherwise re-run on every reconnect and reboot) — publish commands with `retain=false`. |
| `<base>/command/result` | publish | QoS 1, not retained | `{"id":<id>,"ok":true}` or `{"id":<id>,"ok":false,"error":{"code":"...","message":"..."}}` |

Actions: `open` (needs `url`), `set-home` (needs `url`), `home`, `back`,
`forward`, `reload`, `stop`, `restart-webview`, `clear-cache`,
`clear-site-data`, `media-next`, `media-play`, `media-pause` and `set-mode`
(needs `mode`: `qms_only`, `media_only` or `split`; `qms_focus` and unknown
values are refused as `bad_request`). A URL is validated with the same
`UrlValidator` the REST path uses, so `javascript:`/`data:` and the like are
refused (the result reports `invalid_url`). An unknown action name is echoed back
truncated to 32 characters, so the result payload stays bounded.

`set-mode` calls the same `setMediaMode` as `POST /api/media/mode`;
`media-next`, `media-play` and `media-pause` call the same `MediaController`
methods as the on-device menu. With media mode disabled, the three playback
actions are no-ops and report
`{"id":<id>,"ok":true,"applied":false,"reason":"media_disabled"}` rather than
failing, while `set-mode` refuses with the existing `media_disabled` conflict.
Each action has a strict key set: a `url` on `media-next` or a `mode` on
`media-play` is malformed, not ignored.

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

### Home Assistant MQTT Discovery

When MQTT is enabled, the TV also publishes **Home Assistant MQTT Discovery**
configs, so Home Assistant creates one device — **"Terebi Bro"** — with its
entities automatically, with no YAML. Discovery is **on by default** (it is only
meaningful while MQTT itself is on) and its prefix is configurable
(`mqttDiscoveryPrefix`, default `homeassistant`). Both are managed from the
controller's **MQTT** panel and via `GET`/`POST /api/settings/mqtt`.

Configs are **retained** and published on every connect, and re-published on
every reconnect. The topic shape is
`<prefix>/<component>/terebibro_<deviceId>/<objectId>/config`, where
`<deviceId>` is the same stable, sanitised device segment the MQTT client id and
base topic use, so a restart never changes the node id and Home Assistant never
creates a duplicate device. Every payload carries a `unique_id`, the shared
`device` block, `has_entity_name: true` (so HA names each entity relative to the
device and a second TV does not get suffixed ids like `button.reload_2`) and the
`availability` wiring (`online` / `offline`).

The `text` entities set `max: 255` (Home Assistant's own state limit) so a URL up
to the 2048 characters our command parser accepts is not silently rejected by
HA's default `max: 100`. The `Current URL`, `Title` and `Now serving` sensors
truncate their `value_template` with `truncate(250, true)` for the same reason:
values up to 255 characters pass through whole and longer ones are cut to 250,
so the result never exceeds Home Assistant's 255-character state limit. The
`text` `command_template`s build their JSON with HA's `tojson` filter so a value
containing a quote or backslash stays valid JSON instead of being dropped as
malformed.

Changing `mqttDiscoveryPrefix` is safe: the app remembers the prefix it last
published under, and on the next connect it first publishes an empty retained
payload to every config topic under the **previous** prefix, so the old entities
do not linger in Home Assistant.

What appears:

| Entity | Domain | Source |
| --- | --- | --- |
| Current URL | `sensor` | `{{ value_json.url }}` |
| Title | `sensor` | `{{ value_json.title }}` |
| Uptime | `sensor` | `uptimeMs` → seconds, `device_class: duration` |
| WebView version | `sensor` (diagnostic) | `{{ value_json.webViewVersion }}` |
| App version | `sensor` (diagnostic) | `{{ value_json.appVersion }}` |
| IP address | `sensor` (diagnostic) | `{{ value_json.ip }}` |
| Port | `sensor` (diagnostic) | `{{ value_json.port }}` |
| Network | `sensor` (diagnostic) | `{{ value_json.network }}` |
| Android version | `sensor` (diagnostic) | `{{ value_json.androidVersion }}` |
| Loading | `binary_sensor` | `value_json.loading` (`ON` / `OFF`) |
| Connectivity | `binary_sensor` | the availability topic (`online` / `offline`) |
| Reload, Restart WebView, Back, Home, Forward, Stop, Clear Cache, Clear Site Data | `button` | a `command` JSON payload |
| Next, Play, Pause | `button` | `media-next` / `media-play` / `media-pause`; a no-op while media mode is off |
| Mode | `sensor` | `{{ value_json.mediaMode }}` |
| Now serving | `sensor` | `value_json.qmsNow` through `truncate(250, true)`: up to 255 characters pass unchanged, longer values are cut to 250 |
| Home URL | `text` | mirrors `homeUrl`, writes `set-home` |
| Open URL | `text` | writes `open` (no state) |

The sensor/availability entities report on `<base>/status` and
`<base>/availability`; the buttons and text entities publish to `<base>/command`
with the same action names the command parser accepts (`open`, `set-home`,
`home`, `back`, `forward`, `reload`, `stop`, `restart-webview`, `clear-cache`,
`clear-site-data`, `media-next`, `media-play`, `media-pause`).

**Removing it.** Turn discovery **off** in the MQTT panel (or
`POST /api/settings/mqtt {"discoveryEnabled": false}`). The app then publishes an
**empty retained payload** to every discovery topic it owns, so Home Assistant
removes the entities instead of showing them permanently unavailable. A clean
shutdown does *not* remove them by default: the retained `availability` topic
already reports the device offline, which is what availability is for. (Removing
also requires MQTT to be connected at the moment you disable it, since the
removal itself is an MQTT publish.)

Discovery payloads contain **no secrets** — no password, username or token, only
the device id, its name, the app version, the base topic and the prefix.

Each discovery config is published **independently**: one entity that fails to
build or publish is counted and skipped, and never prevents the entities after it
from being published (or the stale-prefix clears from running). The connect-time
burst does not run on Paho's callback thread and the QoS-1 in-flight window is
raised well above the entity count, so a full window cannot silently strand the
configs after the tenth. The result of the last attempt is observable without a
logcat: the retained `<base>/status` payload carries `discoveryTopics` (configs
written), `discoveryError` (a short, redacted reason such as `MqttException: ...`,
or `null`) and `statusError` (a short, redacted reason for a failed retained
`status` or `availability` publish, or `null`), and `GET /api/device` and
`GET /api/settings/mqtt` report the same as `mqttDiscoveryTopics` /
`mqttDiscoveryError` / `mqttStatusError`. A
redacted reason never includes a credential: any `scheme://user:password@host`
user-info is stripped before it is stored.

> Home Assistant may still need manual naming for **history/statistics** you
> record against these entities; discovery creates the entities, not the
> recorder/long-term-statistics configuration.

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

The media surface adds a new, bounded write surface to that model. The QMS
bridge is still **write-only and content-free**: the injected
script is gated to the configured QMS origin, exposes one method, five fixed
signal kinds, a sanitised value of at most 64 characters and a `void` return, so
a hostile page can at worst force a nuisance full-screen flip. Library uploads
go through the same authenticated, CSRF- and Host-checked control server, are
bounded by quota, reserve, concurrency and rate, and validate magic bytes before
they reach the queue. Media is stored in app-private external storage rather
than `filesDir`, so a full disk breaks playback, not pairing or token writes.

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

In a **debug** build the QMS detection path emits extra short, single-line
diagnostics under the `TerebiBro/Qms` tag (`BuildConfig.DEBUG`; absent from a
release build): one line per accepted signal (`qms signal=now|probe|ready|audio`,
with the queue-number key parts for `now`), the probe result plus the resulting
`qmsMatched`/`healthy` flags, the call-key comparison
(`qms call prev=… now=… newCall=…`), the dwell arm/re-arm/expiry with the
remaining seconds, and the existing `media layout mode=… via=call|manual|boot|default`
line. The `media layout` line is emitted only when a pass actually changes the
mode, the band geometry or a surface's visibility — not on the page's
one-second probe heartbeat. They carry no URLs or credentials — the `now`
values are queue numbers.

## Documentation

- `docs/implementation-plan.md` — the original implementation plan (verbatim).
- `docs/v1-scope.md` — what V1 ships and what was deliberately deferred.
- `docs/v2-scope.md` — what V2 (QMS + media) adds: media mode, the split
  layout, QMS detection, the media library and playlist, the chime, the
  trade-off behind each decision, and the verification caveats.

## Verification status

Validated by building and by JVM unit tests:

- `assembleDebug` builds and produces `app-debug.apk`.
- `testDebugUnitTest` passes: 215 JVM tests (PIN/token/auth logic, token registry
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
  write), the discovery builder (topic shape and device-name sanitising, stable
  `unique_id`s, the shared `device` block, `has_entity_name`, the text `max`,
  the truncated URL/title templates, availability wiring, the stale-config
  clearing after a prefix change, and a table-driven check that every
  button/text action resolves through the real command parser, including a
  `tojson` payload carrying a quote and a backslash) and the capped reconnect
  backoff).
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

### V2 media mode

V2 is validated **at the JVM and build level only**:

- `testDebugUnitTest` passes: **600 JVM tests, 0 failures, 1 skipped** (a symlink
  test skipped on Windows). This includes the media reducer and layout, the
  settings validator, the QMS call key, signal clamp and injected-script
  escaping, the media queue and playlist parser, the upload rules, the media
  state JSON, the strict MQTT parser with the four new actions and their key
  sets, the discovery builder with the three new buttons and two new sensors
  (each button payload re-parsed through the real command parser), the
  `command/result` shape (an existing action still reports exactly
  `{"id":…,"ok":true}`, and a disabled media action adds only
  `applied`/`reason`), the chime rule (source gating, the 1 s DOM-call
  debounce, and the `audio` source suppressing the DOM takeover while still
  updating the strip text), the media-web policy (band-scoped fullscreen is
  allowed only in `media_only`/`split` with a web source, and immersive mode is
  not re-applied while the keyboard is up), the independence guard that a
  media playback error cannot change `qmsDetectionHealthy`, `qmsMatched` or
  `qmsAvailable`, and the QMS install predicate (an already-started page is
  evaluated immediately, with or without document-start support).
- `lintDebug` runs with **0 errors**; its 35 warnings are pre-existing
  (`setText` literals and dependency-version notices in `MainActivity`, plus
  manifest and resource warnings), and there are no new warnings in the media or
  MQTT code.
- `assembleDebug` builds and produces `app-debug.apk`.

**Nothing has been verified on a device.** The injected QMS script has never run
against the real vendor page, so selector matching, the `SEDANG DIPANGGIL` /
`PANGGILAN TERDAHULU` strip, the boot rule through the daily activation form and
the chime hook are all unexercised. No upload, playlist fetch, local playback or
remote playback has been exercised at runtime. The media decoder's behaviour
alongside the live WebView, 720p geometry, the 24 h soak and every item on the
device checklist in `docs/v2-scope.md` remain open. Treat V2 as **unverified at
runtime** until that pass is recorded.
