# V2 scope

Terebi Bro V2 adds a **media mode** to the existing kiosk browser so a TV that
is showing a clinic queue-management (QMS) dashboard is not idle while it waits
for a call. The app stays what it is: one Activity
(`app/src/main/java/com/terebibro/tv/MainActivity.kt`), one WebView, no Compose,
no ConstraintLayout, and no new runtime dependency for playback. It remains
recognisably the same app, driven by the same controller, the same pairing and
the same MQTT surface.

This is a scope document, not a design spec for a greenfield app. It states what
V2 ships, what it deliberately does not ship, and the cost of each decision in
plain terms. Where the implementation settled something differently, the
difference is recorded rather than smoothed over — see
[Deviations accepted during implementation](#deviations-accepted-during-implementation)
and [Verification](#verification).

Everything below is conditional on one switch. See
[Deviation: media mode is off by default](#deviation-media-mode-is-off-by-default).

## Shipped in V2

**Media mode (opt-in)**

- `mediaModeEnabled`, default **`false`**. With it off, behaviour and controls
  are as in V1; the state payload carries a few additional fields
  unconditionally (see
  [Deviation: media mode is off by default](#deviation-media-mode-is-off-by-default)).
- Four display modes — `QMS_ONLY`, `MEDIA_ONLY`, `SPLIT`, `QMS_FOCUS` — decided
  by one pure reducer so the mode rules are unit-testable without Android.
- Media mode does not take the screen at launch: the app boots `QMS_ONLY` full
  screen and reaches `SPLIT` only once the dashboard has actually rendered. See
  [Boot and activation](#boot-and-activation).
- `SPLIT` (the default once media mode is enabled and the dashboard is
  rendering) puts the QMS strip in a bottom band under a letterboxed media
  band. That bottom arrangement is the only one — see
  [Deliberately deferred](#deliberately-deferred) for the right-hand variant
  that was specified but not built.
- Automatic promotion to `QMS_FOCUS` on a detected call, held for `dwellSec`
  (default 50 s) and then returned to the previous mode.
- Manual mode override from the controller or MQTT: `MEDIA_ONLY` holds for
  30 minutes, `QMS_ONLY` is held indefinitely because the queue stays visible.

**Call detection**

- DOM observation of the third-party QMS page, with a 1 s interval fallback and
  a `MutationObserver` on the list container.
- The strip is produced by **restyling the vendor's own nodes**, never by
  copying or rewriting their text. A wording change on the QMS requires no app
  change.
- Selector health is probed on every page load and published as
  `qmsDetectionHealthy`; a page that will not load falls back to `MEDIA_ONLY`,
  a selector that stops matching after having matched degrades the strip to a
  native card instead of a stale or invented number, and a selector that has
  never matched since launch leaves the app in `QMS_ONLY` full screen.
- An optional second signal from the page's chime (`callSource`,
  default `"dom"`).

**Media library and playlist**

- Local playback from
  `/sdcard/Android/data/com.terebibro.tv/files/media/`, rescanned on resume, no
  runtime permission and no SAF picker.
- Optional HTTP playlist (`http`/`https` only), strictly parsed, cached
  last-good to disk; the local folder is the source whenever the playlist is
  unavailable or empty.
- An ad-hoc URL (`POST /api/media/open`) that overrides the queue until another
  source is chosen, for "play this one thing now" without staging a file on the
  TV. See [Ad-hoc URL](#ad-hoc-url).
- Upload, delete, list and preview from the controller, streamed to disk under
  quota, free-space reserve, magic bytes and a metadata check.

**Signage language**

- `uiLanguage`: `ms` (default) | `en` | `zh` | `ta`, applied to the
  patient-facing signage strings only. The vendor's own strings are never
  translated.

**Integrations**

- Seven new paths, eight method/path pairs, on the existing authenticated
  control server.
- Additive MQTT actions and Home Assistant entities. No protocol change: the
  new state fields ride the existing single fan-out into `BrowserState` /
  `StateJson` / the WebSocket `state` broadcast / the retained `<base>/status`.

Anything not listed here is not covered. The explicit list is in
[Deliberately deferred](#deliberately-deferred).

## Deviation: media mode is off by default

V2's headline feature ships turned off. `mediaModeEnabled` defaults to `false`,
and with it off V2 takes the V1 code path: the same Back ladder, the same
overlays, the same pairing and token flow, the same MQTT commands, the same
controller.

**Why.** The app is installed in a clinic and is already doing a job. A release
that changes the picture on the wall, the Back behaviour or the focus handling
by default is not a feature, it is an outage. The owner switches the media mode
on when they are standing in front of the TV and can see the result.

**The trade-off, stated plainly.** A fresh install that the owner expects to
"do media" shows the queue page alone until someone opens the controller and
enables it. That is the intended failure mode: nothing happens, rather than
something unexpected happening on a clinic wall.

**Blast radius.** Bounded by construction rather than by discipline:

- Every V2 code path that can change what is on screen — the bands, the strip,
  `QMS_FOCUS`, the media host, the injected script, the mode reducer — is
  entered only from a `mediaModeEnabled == true` check. With it off, no
  `MediaPlayer` is created, no script is injected, no `SurfaceView` is attached
  and the WebView is laid out exactly as in V1.
- The exception is the **upload and library routes**, which are reachable and
  simply report an empty library while media mode is off. They exist so the
  owner can stage files before switching on, and they never touch playback.
- Turning the setting off at runtime returns to `QMS_ONLY` and releases the
  player; it does not require a restart.

**The one thing that does change.** The new state fields — `screenWidthDp`,
`screenHeightDp`, `densityDpi`, `renderScale`, the `media*` and `qms*` fields —
are published in `/api/device`, the WebSocket `state` broadcast and the retained
MQTT `<base>/status` **even when media mode is off**. Behaviour is unchanged;
the payload simply carries additional fields. A controller or MQTT consumer that
ignores unknown keys is unaffected, which is how the existing envelope and
`BrowserState` fan-out already work.

The first item on the device checklist is "V1 unchanged with media mode off",
because that is the property this whole document depends on.

## Deviation: framework `MediaPlayer`, not `androidx.media3`

Playback uses the platform `MediaPlayer` + `SurfaceView` for video and an
`ImageView` for stills. The framework `MediaPlayer` is the same vendor codec path
the device already uses for the WebView's own video, and it is present without
adding a dependency.

**Why.** `androidx.media3`/ExoPlayer adds about 1.5–2 MB (estimated, not
measured) plus transitive AndroidX to an app that has no AndroidX UI at all, and
it routes decode through its own `MediaCodec` configuration, which cheap TV SoCs
are *expected* to handle worse than their own player — that is the premise, not a
measurement from this project. The vendor decoder path is the one observed to
work on the reference unit.

**The trade-off, stated plainly.** The engine is narrower than ExoPlayer:

- **H.264/AAC in MP4 only, plus still images** — no H.265/AV1, no HLS/DASH.
  MP4, JPEG and PNG are the whole supported set. For signage that is acceptable:
  the clips are short, static, and produced by the clinic.
- Anything the platform decoder refuses fails with `mediaError` and is skipped,
  not retried forever.
- **A 1–3 frame seam on loop or advance** when only one decoder is available. A
  seam-free handoff needs a second decoder, so `setNextMediaPlayer()` is used
  **only when the device reports one available**; otherwise the loop or the
  advance has the seam and that is accepted. The single-item `setLooping(true)`
  path is *expected* to have no seam on this hardware; that has not been
  measured.
- A **visible gap on queue advance** when no next item has been prepared. There
  is no preload beyond that one prepared item, because preloading costs the
  second decoder the rule above already refuses to assume.

**Blast radius.** The engine is behind a small host interface
(`MediaPlayerHost`), so a future replacement is a contained change. Nothing
outside the media band knows which class is playing.

## Deviation: binary upload bodies beside the JSON reader

Today `app/src/main/java/com/terebibro/tv/server/BodyFraming.kt` is JSON-only: it
rejects any `POST` without `application/json`, caps the body at 8 KiB and
`readBody()` in `ApiRoutes.kt` materialises the whole body in memory. V2 adds a
**binary body path** for uploads: a `BodyKind.Binary` variant in the
`BodyFraming.Decision` hierarchy, and a separate `BodyFraming.decideUpload(...)`
entry point that streams to `*.part` on disk.

**Why.** A 256 MiB file cannot be framed by, or buffered through, a JSON reader
with an 8 KiB cap. Bolting a special case into `decide()` would weaken the rules
that make the JSON path safe (chunked bodies refused, `Content-Length` required,
no body on `GET`).

**The trade-off, stated plainly.** There are now two body paths to keep correct,
and the binary one writes to disk before it can finish validating. The order of
validation is chosen so the worst case is a partial file, never a forwarded
body: length first, then quota, then reserve, then the write, then the magic
bytes and the metadata check, then the atomic rename.

**Blast radius.** Two rules hold and are reviewed as such:

- The existing JSON path is **untouched** — same function, same cap, same
  rejections, same tests.
- The security check order stays **verbatim**: peer → route/method → Host →
  Origin → CSRF → framing → bearer token → rate limit → strict JSON. The binary
  path slots into the *framing* step and replaces only the last step (strict
  JSON is not applicable); it does not move, skip or merge any check.

## Deviation: media storage outside `filesDir`

Uploaded and local media live under `getExternalFilesDir(null)/media/`, not
under `filesDir`.

**Why.** A full disk must never be able to break token or config writes.
Losing pairing on reboot because someone filled the TV with promos is the worst
failure this product has, and it is entirely self-inflicted if media shares the
volume that holds the token registry.

**The trade-off, stated plainly.** Media is on shared external storage, so
another app on the TV with storage permission can read and delete the files, and
`adb` can push into the folder. In a clinic kiosk that is acceptable: the
content is a public promo loop, and the upload path is already the sanctioned
way to change it. The app-private external directory needs no runtime
permission on the supported API levels, which is why the folder is reachable at
all.

**Blast radius.** Token, PIN and configuration storage are untouched. Uploads
are additionally bounded by quota, free-space reserve and one-at-a-time
replacement, and `*.part` files are swept at boot.

## Extension: the strip is a restyle, not a re-implementation

The QMS page is third-party and cannot be modified. V2 observes its DOM and
restyles its nodes; it does not scrape, copy, cache or re-render the vendor's
text.

**Why this is a hard rule.** The location field is not always a counter. It may
read `Kaunter`, `Bilik`, a room name or a person's name, and it changes when the
clinic reconfigures the QMS. A mapping table or an abbreviation scheme would
break the first time the wording changes, and the break would be silent — the
strip would display something plausible and wrong. Restyling the vendor's own
nodes means a wording change requires **zero app changes**.

**The trade-off, stated plainly.** The app now depends on the vendor page's
structure — element nesting and the number of children — rather than on its
strings. Structure is more stable than wording, but it is not a contract. The
mitigation is the probe: a selector that stops matching is reported within 15 s
and the strip degrades to a native "queue board unavailable" card. It never
displays a stale or invented number.

**Blast radius.** Detection is read-only and lives in the injected script plus a
small pure JVM layer. It cannot write to the page, cannot navigate, and holds no
credentials. Detail: [Detection](#detection).

## Extension: audio chime as an optional second signal

The injected script can patch `Audio`, `HTMLMediaElement.prototype.play` and
`AudioContext` to report `audio_started` alongside the DOM signal.

**Why.** Some QMS deployments chime on every call. A second signal that agrees
with the DOM costs nothing; one that fires when the DOM has not changed yet can
promote the full-screen view a second earlier.

**The trade-off, stated plainly.** The chime is a heuristic, not a contract. Its
failure modes are documented rather than fixed:

- A chime generated purely in Web Audio **may be missed on the fallback
  injection path** (the page has already been parsed when the hook is installed).
- A `<video>` element used as a sound source is **indistinguishable from
  programme audio**, so `callSource: "audio"` alone would trigger on any video
  the page ever plays. That is why `"audio"` alone is not recommended.
- A chime played by a **native** app on the TV is invisible to the WebView
  entirely; no injected script can see it.

**Blast radius.** `callSource` defaults to `"dom"`. `"dom_or_audio"` is opt-in,
and an audio-only signal can never invent a call number — it can only move an
already-detected call forward or (in `"dom_or_audio"`) trigger the focus
transition without a number, which the strip renders as "no calls yet".

## Extension: signage language selector

`uiLanguage` (`ms` default, plus `en`, `zh`, `ta`) selects the language of the
**patient-facing signage strings only**.

**Why.** A clinic's waiting room is not monolingual, and these five strings are
the only words the app itself puts on screen: the two region labels, the no-call
placeholder, and the two unavailability notices.

**The trade-off, stated plainly.** This is not localisation. **The vendor's own
strings are never translated.** The header's date (`Rabu, 4 November 2026`),
`Kaunter`, `Imbas Di Sini` and everything else stay exactly as the QMS renders
them, because they are the vendor's text and the app has no right to rewrite it.
Selecting English does not turn `Rabu` into `Wednesday`, and that is not a bug
to be "fixed" later. The controller UI also stays English: it is staff-facing,
not patient-facing, and translating it would double the surface for no clinical
benefit.

**Blast radius.** Five strings, one pure map
(`SignageStrings`), one completeness test. A full i18n framework is explicitly
rejected — there is no plurals, no formatting, no locale negotiation, and adding
`ResourceBundle`-style machinery for five strings would be a liability, not a
feature.

**Font risk, stated now.** CJK and Tamil glyph coverage depends on the device's
system font set. Cheap TV boxes ship stripped font sets and will render tofu
(□). The mitigation is a device check on the reference unit; if glyphs are
missing, either bundle a subset font (APK size cost, stated before shipping) or
fall back to English. Decided after the device check, not before.

The strings below are **machine-translated (Google Translate) and have not been
reviewed by a native speaker.** The five strings are short, concrete and
unambiguous in context, so machine translation is an acceptable starting point
rather than a liability — but a wrong patient-facing label is a communication
problem, not a cosmetic one, and having one staff member read them costs
nothing:

| key | ms | en | zh | ta |
| --- | --- | --- | --- | --- |
| `labelCurrent` | SEDANG DIPANGGIL | NOW CALLING | 正在呼叫 | அழைக்கப்படுகிறது |
| `labelHistory` | PANGGILAN TERDAHULU | PREVIOUS CALLS | 之前的呼叫 | முந்தைய அழைப்புகள் |
| `noCall` | TIADA PANGGILAN | NO CALLS YET | 暂无呼叫 | அழைப்புகள் இல்லை |
| `qmsUnavailable` | PAPAN GILIRAN TIDAK TERSEDIA | QUEUE BOARD UNAVAILABLE | 叫号板不可用 | வரிசை பலகை கிடைக்கவில்லை |
| `mediaUnavailable` | MEDIA TIDAK TERSEDIA | MEDIA UNAVAILABLE | 媒体不可用 | மீடியா கிடைக்கவில்லை |

Delivery constraints that matter for correctness:

- The dictionary is a pure `SignageStrings` map (language → key → string) with a
  unit test asserting every key is present in every **shipped** language,
  non-blank, with no extra entries.
- Values reach the page as **CSS custom properties**, so the `::before` labels
  stay React-proof and detection-proof (see [Labels](#labels)).
- Each value is **CSS-escaped** (`"`, `\`, control characters) before it is
  embedded. A Tamil or CJK string must not be able to terminate the declaration.
- Missing glyphs are a device property, not an app property; the app does not
  detect tofu and silently substitute.

## Modes

Four modes, one pure reducer. The reducer takes the current mode, the settings,
the detected call state and a wall clock, and returns the next mode; the effect
layer only reacts.

| Mode | What is on screen |
| --- | --- |
| `QMS_ONLY` | V1 exactly: the vendor page full screen. |
| `MEDIA_ONLY` | The media band only, full screen. |
| `SPLIT` | Media / header / queue bands (default when media mode is on). |
| `QMS_FOCUS` | The vendor page full screen for the duration of a call. |

Transitions:

| Event | From | To | Notes |
| --- | --- | --- | --- |
| Launch, before anything is checked | — | `QMS_ONLY` | Full screen, regardless of `mediaModeEnabled`. |
| Media mode enabled | `QMS_ONLY` | `SPLIT` | Only once the strip selector has matched at least once since launch. |
| Strip selector matches for the first time since launch | `QMS_ONLY` | `SPLIT` | The dashboard is rendering; the activation page is behind us. |
| Call detected | `SPLIT` | `QMS_FOCUS` | Timer armed for `dwellSec`. |
| Second call during `QMS_FOCUS` | `QMS_FOCUS` | `QMS_FOCUS` | Timer **re-armed**, no flicker, no re-layout. |
| `dwellSec` elapsed | `QMS_FOCUS` | previous mode | The mode that was active before the call. |
| Manual override (controller/MQTT) | any | requested | `MEDIA_ONLY` holds 30 min, `QMS_ONLY` indefinitely, `SPLIT` drops any hold. |
| Call detected during a manual hold | manual mode | `QMS_FOCUS` | Returns to the manual mode only if its hold has not expired; otherwise to `SPLIT`. |
| **Page-level** QMS failure — the page will not load, the renderer crashes, the QMS origin is unreachable | `SPLIT` or `QMS_ONLY` | `MEDIA_ONLY` | There is no strip to show and nothing to activate. With media mode off the reducer is inert and the V1 error page stays full screen. |
| **Selector-level** failure, selector has **never matched since launch** | `QMS_ONLY` | `QMS_ONLY` | Stays full screen; the page may be the daily activation form. |
| **Selector-level** failure, selector matched before | `SPLIT` | `SPLIT` | Layout unchanged; the strip renders the native `qmsUnavailable` card. Never a stale number. |
| `QmsAvailable` | `MEDIA_ONLY` (degraded) | `SPLIT` | Restores the previous layout. |

The failure cases are deliberately different. A page-level failure leaves
nothing to restyle, so the media band takes the screen — including at launch,
where `QMS_ONLY` goes straight to `MEDIA_ONLY` because a page that never loaded
has nothing to activate. A selector-level failure leaves a working page whose
structure changed, so the strip stays where it is and says so; blanking the
whole board because one selector moved would hide the media as well and make
the fault look worse than it is. Before the selector has ever matched there is
nothing to degrade from, so the app does not degrade at all — see
[Boot and activation](#boot-and-activation).

`QMS_ONLY` and `MEDIA_ONLY` are also selectable manually. The hold follows the
same principle as the boot rule: **a manual mode that hides the queue gets a
timeout; one that shows it does not.**

| Manual mode | Hold | Why |
| --- | --- | --- |
| `MEDIA_ONLY` | **30 minutes**, then back to `SPLIT` | long enough to configure and preview |
| `QMS_ONLY` | **indefinite** | it is the safe state — the queue is visible |
| `SPLIT` | not applicable | the automatic default |

A call arriving during a manual hold still takes `QMS_FOCUS` and returns to the
manual mode only if its hold has not expired — otherwise it returns to `SPLIT`.

### Boot and activation

The QMS requires a code and a button press once a day before it shows the
dashboard. That form is served at the QMS URL, and a 238 px strip makes it
unusable, so **media mode does not engage until the dashboard is actually
rendering.** The rule in one sentence: the strip selector must have matched at
least once since launch before any layout other than full-screen `QMS_ONLY` is
allowed.

| Situation | Result |
| --- | --- |
| Launch, before anything is checked | **`QMS_ONLY`** full screen, regardless of `mediaModeEnabled` |
| Page loaded, strip selector has **never matched since launch** | **stay `QMS_ONLY`** — the operator may be looking at an activation form |
| Page loaded, selector matched before, now does not | **stay `SPLIT`** with the native `qmsUnavailable` card |
| Page cannot load at all (origin or network failure) | **`MEDIA_ONLY`** — there is nothing to activate; the strip area shows the unavailability notice and the existing retry keeps running |

The reason is plain: **the morning activation must get the whole screen, and the
app must not learn to hide the one screen the operator needs.** Degrading to
`SPLIT` on a page whose selector has never matched would put a dashboard that is
not there into the bottom fifth of the screen, with the code form crushed into
it. Staying full screen costs a media loop that has not started yet, which is
nothing.

The table governs **when media mode may engage**, so it assumes
`mediaModeEnabled` is true. With it off the reducer is inert and every row
collapses to V1: `QMS_ONLY`, with the WebView's own error page when the page
cannot load.

**A renderer crash or a Refresh WebView returns to `QMS_ONLY`**, because the
reload lands on the activation page, and the probe history resets with it. The
history is a per-launch fact by construction: it is not stored, so a restart
cannot inherit a match from before the reboot.

## SPLIT layout

Bands at 1080p:

| Band | Share | Height at 1080p |
| --- | --- | --- |
| Media | 71 % | 766 px |
| Header | 7 % | 76 px |
| Queue | 22 % | 238 px |

The bands are percentages of the screen; the pixel column is the 1080p surface
those percentages were authored against. The header and queue bands are computed
first, on purpose: each is derived from its own percentage and the media band
takes the remainder. That is why 71/7/22 is **766/76/238** and not 767/76/237 —
7 % of 1080 rounds to 76, 22 % rounds to 238, and 1080 − 76 − 238 = 766. Every
typography and card dimension in this document is likewise authored at 1080p and
multiplied by `renderScale = screenHeightDp / 1080` at runtime — see
[Resolution independence](#resolution-independence). There is no second design
for a 4K panel.

A 720p panel reports **540 dp** or **720 dp**, depending on the device's density
setting, so the factor is **0.5 or 0.667**. The document does not assume which;
the value follows whatever the device reports. The split is a pure function
(`MediaLayout.split`) that rounds to integers and asserts the parts always sum to
the screen height, because a 1 px overlap is a visible seam and a 1 px gap is a
black line across the TV.

Only the bottom arrangement ships. `stripPosition = "right"` — the two strip
bands in a right-hand column, with the media band taking the remaining width —
was specified and validated but never implemented, so the setting was **removed**
rather than published as a switch that does nothing. It is listed in
[Deliberately deferred](#deliberately-deferred).

### Media band

`MediaPlayer` + `SurfaceView` for video, `ImageView` for stills.

- **Default fit is `contain` (letterbox).** The whole frame is visible and
  nothing is cropped. This is the default because cropping a clinic promo can
  cut off a phone number or a logo, and that has no visible symptom.
- `mediaFillMode` defaults to **`letterboxBlur`**: a darkened, scaled copy of
  the same frame fills the bars behind the letterboxed content. No blur filter
  is applied — the name describes the look, and a real blur would cost a render
  pass per frame on hardware that has none to spare. `letterbox` is the same
  layout with flat black bars instead.
- **`cover` exists as an opt-in and does crop.** It uses the whole band by
  cutting the overflow, so it is the only mode where content can be lost, and
  choosing it accepts that.
- The **≈71 % area figure applies to the letterbox modes only** (16:9 content in
  a 1920×766 band fills 1362/1920), so roughly a quarter of the band is fill.
  That is the price of never cropping, and it is stated here rather than
  discovered later. `cover` uses the full band by cropping.

### Header band

The vendor page's own `<header>`, restyled: logo, clinic name, date, clock. The
page's **fullscreen button is hidden in strip mode** — a stray D-pad activation
would push the app into its HTML5 custom-view path mid-signage, replacing the
whole layout with a full-screen video element nobody asked for.

### Queue band

Current call card on the left, history cards to the right.

- **Current card**: a fixed slot of 360 px at 1080p (`currentCardPx`), so a long
  location name does not move the history row. Number at 96 px at 1080p
  (`currentNumberPx`), number/location/time stacked, yellow as the page already
  styles it, with a vertical `border-right` divider.
- **History cards**: content-sized, `min 190 px`, `max 320 px` at 1080p
  (`historyCardMinPx` / `historyCardMaxPx`), after which the location wraps to
  two lines. Number at 56 px at 1080p (`historyNumberPx`), number/location/time
  stacked.
- **Right-edge fade** (`-webkit-mask-image: linear-gradient(to right, #000 88%,
  transparent)`) signals that more calls exist off-screen.
- Newest first, static.

### Labels

`SEDANG DIPANGGIL` above the current card and `PANGGILAN TERDAHULU` above the
first history card, implemented as CSS `::before` on the cards — **not** as DOM
nodes. Labels are set at `labelPx` (15 px at 1080p) and scaled by `renderScale`
like every other dimension. Two reasons, both load-bearing:

1. React re-renders cannot wipe a pseudo-element. A label inserted as a child
   node would be removed on the next vendor re-render and the band would lose
   its caption until the observer ran again.
2. `::before` content is not part of `textContent`, so it cannot contaminate the
   detection key. A DOM label would be read back by the observer as part of the
   card's text and change the call key.

### No motion

Static row, newest first. An optional 1.5 s highlight pulse on the newly arrived
card (`newCallHighlight`, default on). **No marquee and no step-shift
animation.** A continuous full-width repaint competes with H.264 decode on the
target hardware, and moving digits are harder to spot at three metres than a
card appearing in a fixed place.

### No overlay

The queue band is a **sibling** of the media band, not composited over it.
Overlaying a WebView on a `SurfaceView` has been observed to glitch on this class
of cheap TV box with hardware overlay (not proven for every unit); keeping the
two regions disjoint preserves the cheap, known-good `SurfaceView` path. A true
"lower third" would require `TextureView` and its measured cost. That is
deliberately deferred.

## Resolution independence

**The rule in one sentence.** V2 is authored at a 1080p baseline; at runtime one
factor, `renderScale = screenHeightDp / 1080`, is computed once and applied to
native view dimensions, to the injected strip stylesheet and to the vendor
page's `setInitialScale`. One factor, one place it is computed, and no other
pixel value stored anywhere.

**The design target is 1080p.** The app lays out for a 1080p surface and the
television (or the platform's density scaling) upscales it to 4K. There is no 4K
layout and adding one is out of scope. `renderScale` is therefore a one-line
guard, not a feature: on a density-scaling 4K panel it evaluates to exactly `1.0`
and changes nothing; it exists only so that a device reporting a *native* 2160 dp
surface does not render the interface at half size.

**Why dp and not px.** A 4K panel that density-scales reports 1080 dp and 2160
*px*, so a px-based factor would double a layout the system had already scaled
for us. dp is the space the app lays out in, and it is the same space the
WebView's CSS pixels live in. Native view dimensions are therefore in dp, not
raw pixels, for exactly the same reason.

- **Native dimensions** — band heights, card slots, label sizes — multiply the
  authored 1080p value by `renderScale`, in dp.
- **The injected stylesheet** is built with the scaled values, or emits
  `--terebi-scale` and uses `calc()`. `calc()` with the factor is preferred so
  the authored numbers stay readable in the source. The WebView viewport *is*
  the band, so `vh`-based sizing would also work, but then the numbers stop
  being the numbers in this document.
- **The vendor page in `QMS_FOCUS`** gets
  `setInitialScale(round(renderScale * 100 * uiScale / 100))`. At 1080p that is
  100 and changes nothing; at native-dp 4K it renders the dashboard at 2× so it
  looks as the vendor intended instead of half-size.

**Both 4K device classes.** A 4K panel that density-scales (3840 physical px at
320 dpi) reports **1920×1080 dp** to apps, so `renderScale` is `1.0` and nothing
changes; it still renders sharp because the system composites at 4K. That is the
common case. A device that reports native dp (3840×2160 at 160 dpi) gets
`renderScale = 2.0` automatically and everything doubles proportionally — the
same picture, bigger. Neither can be assumed, so the app measures the surface it
was given rather than inferring the panel.

**Video stays capped at 1080p30 H.264/AAC-LC even on a 4K panel.** The TV
upscales, which at three metres is indistinguishable; native 4K decode
quadruples the surface buffer and the SoC load on exactly the hardware class
this app targets. This is deliberate, not an omission.

**The cost of the approach, stated plainly.** Uniform scaling enlarges text and
layout together, so a 4K device does not get more history cards or smaller gaps
— it gets the 1080p design at 2×. That is the intended trade: one predictable
design, verified once, rather than a responsive system nobody will test on every
panel. If a specific 4K unit ever needs a different density, `uiScale` is a
manual correction for the vendor page, not a second design. It is not applied to
the media band or to the strip.

**Raster cost.** The strip band at 4K is about 1.8 M px versus about 0.45 M at
1080p — acceptable for a static row. Full-screen `QMS_FOCUS` at 4K is about
8.3 M px, which a weak TV GPU will feel; that is a further reason `QMS_FOCUS` is
a dwell-limited state rather than a permanent one.

**Diagnostics.** `screenWidthDp`, `screenHeightDp`, `densityDpi` and the derived
`renderScale` are published in state and in `GET /api/device`, so the device
class and the factor actually in use are read from the Device panel rather than
inferred from how the text looks.

## QMS_FOCUS

On a detected call the media band and the strip are hidden, the WebView is
resized to the full screen surface (`screenWidthDp` × `screenHeightDp`; 1920×1080
on the reference unit), and the `data-terebi-strip` attribute is removed from
`<html>`. The patient sees the vendor's intended full dashboard — both columns,
hero number, QR block — not a cropped restyle.

**The trade-off, stated plainly.** This is a full-screen takeover: for the length
of the dwell the media loop disappears and the patient sees the vendor's
dashboard only. That is the point — a call is the one moment the queue matters
more than the promo — and the dwell bound (20–180 s) is what keeps it from
becoming a mode nobody chose. A persistent split would show both and make
neither legible at three metres.

The layout pass happens **once on entry and once on exit**. No animation is
applied to the surface: a transform or alpha animation on a `SurfaceView` is
either ignored or glitches, and resizing is the mechanism the hardware
understands.

If a second call arrives during focus, the dwell timer is re-armed and the
layout is not touched again.

## MEDIA_ONLY

Three states, and the difference between them is what the operator is working
on:

```
QMS_FOCUS   full-screen QMS    - you work in the dashboard (activate, log in)
MEDIA_ONLY  full-screen media  - you work on media (pick a source, test it)
SPLIT       on air             - both, hands off
```

`MEDIA_ONLY` is a **media workspace**, not a display mode to sit in. It exists so
that setting media up happens on the TV, with the whole screen, instead of
through a phone while the panel shows a strip.

- Entering `MEDIA_ONLY` shows full-screen media **and opens the media panel**;
  closing the panel leaves pure playback. The picture is the thing being judged,
  so the controls do not cover it once the choice is made.
- **The panel is the existing on-device menu**, not a second overlay: the media
  rows are added to `menu_overlay` and opened by the same MENU/INFO key (or
  long-press Back). Reuse is deliberate — focus handling, D-pad traversal and the
  Back ladder already exist there, and a second overlay would be a second set of
  those to keep correct.
- Menu rows, shown only when media mode is enabled: **Play / Pause**, **Next
  file**, **Play from URL…** (reusing the existing Enter URL dialog pattern),
  **Choose media…** (a D-pad list of local files), and **Media mode** (QMS only /
  Media only / Split).
- **Back in `MEDIA_ONLY`** closes the panel if it is open, otherwise returns to
  `SPLIT`. Without this rung, Back would fall through to the WebView ladder and
  navigate the QMS page away from the dashboard.

## Detection

### The rule

The strip is produced by **restyling the vendor's own nodes**, never by copying
or rewriting their text. The location field is not always a counter
(`Kaunter`, `Bilik`, a room name), so a mapping table or abbreviation would break
the first time the QMS changes wording. A wording change must require **zero app
changes**.

### Structural selectors

Observed from the vendor page, not verified against a captured fixture — there
is no vendor markup in this repository, so none of these selectors has ever been
matched against captured HTML. They are configurable, with these defaults:

| Element | Selector | Setting |
| --- | --- | --- |
| Left column | `main > div:nth-child(1)` | `qmsStripSelector` |
| List container | `main > div:nth-child(1) > section > div` | — |
| Cards | list container `> div` | — |
| Current card | `:nth-child(1)` | — |
| Number | `div:first-child > span` | `qmsNumberSelector` |
| Location | `div:nth-child(2) > span:first-child` | `qmsLocationSelector` |
| Time | `div:nth-child(2) > span:nth-child(2)` | `qmsTimeSelector` |

The right column (`main > div:nth-child(2)` — hero number and QR block) is
hidden in strip mode.

A configured selector is validated as **one selector in one scope**. A value
containing a comma, a CSS comment (`/*` or `*/`) or an unbalanced double quote is
rejected, because a comma starts a second selector outside the
`html[data-terebi-strip]` scope, a comment swallows every rule after it, and an
odd quote opens a string that eats the next rule's prelude. The stylesheet
builder re-checks and falls back to the slot's default, because a value recovered
from stored state can bypass the validator; either way the selector cannot escape
the stylesheet scope.

### Call key

**Call key = `number | location | time`.** Whitespace is normalised **for the
key only**; display is always verbatim, because a normalised copy on screen is a
rewritten copy.

The key includes the location and the time for one specific reason: re-calls.

```
A1001 → Kaunter 1 → A1001 → Kaunter 3
```

must fire **twice**. A naive "did the number change" comparison sees `A1001`
before and after and reports no second call — the patient is called to a
different counter and nothing happens on screen.

A **wholesale replacement of every list child** is a page re-render, not a call.
The observer compares the set of keys before and after; if the previous keys
reappear unchanged, no signal is emitted.

### Signals

- `MutationObserver` on the list container, plus a **1 s interval** fallback for
  renderers/pages where the observer does not fire (the reference device runs
  Chromium 66, and the fallback is not theoretical there).
- All signals are clamped to **500 ms**, so a rapid double-render or an
  observer-plus-interval coincidence cannot produce two transitions.

### Injection

- `WebViewCompat.addDocumentStartJavaScript` when
  `WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)` is
  true.
- Otherwise, injection in **both** `onPageStarted` and `onPageFinished`; either
  may be the first callback on a given device, and installing twice is
  idempotent by design (the script sets a guard flag).
- **The reference device runs Chromium 66, which does not support document-start
  scripts.** On that path the DOM watch still works — the script is installed at
  `onPageStarted` and the observer starts then — while the audio hook is
  best-effort, because the page may already have created its `AudioContext`.
  `documentStartSupported` is published in state so this is visible rather than
  guessed at.

### Selector health

Every page load probes `querySelectorAll` for the list container, the current
card and the three field selectors, and publishes `qmsDetectionHealthy`. The
failure modes are handled differently on purpose:

- **Selector-level failure** — the page loaded, but the structure changed and
  the probe does not match. If the selector has matched before, the app **stays
  in `SPLIT`** and the strip renders a native `qmsUnavailable` card in place of
  the cards. No numbers are displayed, not even the last known ones. The probe
  retries on the 15 s interval and restores the strip when it passes. Blanking
  the whole board for a moved selector would also hide the media, and would make
  a recoverable fault look identical to a dead page. If the selector has **never
  matched since launch**, there is nothing to degrade from and no evidence that a
  strip ever existed: the app **stays in `QMS_ONLY`** full screen, because the
  page may be the daily activation form — see
  [Boot and activation](#boot-and-activation).
- **Page-level failure** — the page will not load, the renderer crashes, or the
  QMS origin is unreachable. There is nothing to restyle, so `SPLIT` transitions
  to `MEDIA_ONLY` and the media band takes the screen; at launch the same case
  goes straight from `QMS_ONLY` to `MEDIA_ONLY`.

A stale number on a queue board is worse than no number: it sends a patient to a
counter that is not calling them.

## Media content

### Local folder

`getExternalFilesDir(null)/media/` →
`/sdcard/Android/data/com.terebibro.tv/files/media/`.

- App-private external storage: no runtime permission, no SAF picker (unusable
  with a D-pad), no USB ritual.
- `adb push` works, and is the M1 demo path.
- The folder is rescanned on resume, so a push while the app is in the
  foreground appears without a restart.
- `mediaLocalEnabled` (default true) turns the folder off without touching the
  playlist.

### HTTP playlist

A JSON list of `{type, url, durationSec}` with `type` in `image`/`video` and
`durationSec` bounded to **3–120**.

- `http`/`https` only; any other scheme rejects the entry, not the playlist.
- Strict parsing: top-level array, known keys only, no duplicate keys, no
  unknown keys.
- Bounded: **≤ 200 items**, **≤ 256 KiB**.
- Refreshed every `playlistRefreshSec` (default 300); a failed refresh keeps the
  last-good copy from disk.

**Playlist or local folder, never both.** The queue is **the playlist** when
`playlistEnabled` is true and the playlist has at least one valid entry;
otherwise it is **the local folder**. The two are **not merged and not
interleaved** — ordering across sources is a scheduling question this version
does not answer, and a mixed queue would make "why did that clip play"
unanswerable. The one exception is the [ad-hoc URL](#ad-hoc-url), which replaces
the queue entirely until another source is chosen. If `playlistEnabled` and
`mediaLocalEnabled` are both false, media mode has no source and the media band
shows the `mediaUnavailable` notice.

**Blast radius.** A malicious or broken playlist can only queue `http`/`https`
URLs for playback. It cannot navigate the browser, read state, or reach the
injected sink: the playlist is a data structure consumed by the media host, not
instructions to the WebView.

`playlistEnabled` defaults to **false**: an install with no server configured
must not spend its life retrying one.

### Ad-hoc URL

A third source, for "play this one URL now" without staging a file on the TV.
`POST /api/media/open` with `{"url": "..."}` **overrides the queue until another
source is chosen**. It is a selection, not a setting: it changes neither
`playlistEnabled` nor `mediaLocalEnabled`, and choosing another source replaces
it.

- The route mirrors `/api/nav/open` **exactly**: the same `UrlValidator`,
  `http`/`https` only, and anything else rejected with `invalid_url`.
- `mediaSource` gains `"url"` alongside `"local"` and `"playlist"`, so the
  controller can show which source is playing.
- **Single items loop like any other item.** A one-item queue is the existing
  `setLooping(true)` path, so the URL plays again rather than parking on the last
  frame.
- It is a media source, not a navigation: the URL is fetched by the media host
  and cannot move the WebView. Navigating is what `/api/nav/open` is for.

**Blast radius.** An ad-hoc URL is the same class of input as a playlist entry —
an `http`/`https` envelope fetched by the player — and passes the same
`UrlValidator` before it reaches the queue. It cannot navigate the browser, read
state, or reach the injected sink.

### Audio policy

`AudioManager` focus (`AUDIOFOCUS_GAIN`) on play, abandon on pause, pause on any
loss, and resume only when we paused **because of** focus — tracked by a
`resumeOnFocusGain` flag separate from the call path, so a focus gain cannot
resume playback that the dwell timer paused.

Media is **paused, not muted**, during `QMS_FOCUS`. A muted player still holds
the audio session and the decoder; a paused one does not, and the chime and the
announcement are audible.

`volumePercent` defaults to 100. A waiting room will usually want 0, and the
setting exists for exactly that; it is not an oversight that 100 is the default
(the M1 demo is unintrusive only because the test clips are silent).

### HLS, HEVC and Jellyfin

Three things the media band is sometimes assumed to do and does not. They are
grouped here because each one is answered by the same sentence: the supported set
is MP4 (H.264/AAC), JPEG and PNG, and anything else is either a device property
or a different feature.

**HLS is not supported in V2.** The media band accepts MP4 (H.264/AAC), JPEG and
PNG. Android's `MediaPlayer` does have platform HLS support (`m3u8`), so
accepting `.m3u8` in the playlist is a candidate **experiment in M3** — same
player, no new dependency — but HLS behaviour on TV boxes varies by vendor
implementation and is not promised until measured. If adopted, the playlist
validator gains the `.m3u8`/`video/mp2t` case and the `type` enum stays `video`,
because the queue asks "video or still", not "how is the video packaged".

**HEVC is not supported by the app, and cannot be added by the app.** Decoding is
the SoC's hardware decoder; the app can only hand the file to it. A box whose
`MediaCodec` reports HEVC may play it through `MediaPlayer` or ExoPlayer; a box
without it does not gain the capability from any dependency choice. Do not
describe ExoPlayer as adding HEVC.

**Jellyfin is not the media band.** It is a web application, so it would run in
the existing WebView as an ordinary page, playing HLS through `hls.js` over Media
Source Extensions. That path is bounded by the device's WebView: the reference
unit runs Chromium 66, which predates syntax the Jellyfin web client relies on
(optional chaining, nullish coalescing, `Promise.allSettled`,
`Array.prototype.at`), so it is **expected** to fail there and **expected** to
work only on a device with a substantially newer WebView. It is also outside
media mode — the split applies only on the configured QMS origin — so navigating
to Jellyfin returns the browser to full screen and the queue band is not shown.

**What would support HLS properly** is `androidx.media3`/ExoPlayer in the media
band: HLS and DASH parsing, correct fragmented-MP4 and audio-track handling,
better buffering. It does not add HEVC. It carries the dependency and
decode-path change this scope deliberately avoids — see
[Deviation: framework `MediaPlayer`, not `androidx.media3`](#deviation-framework-mediaplayer-not-androidxmedia3)
— and it is the upgrade path if the clinic ever wants Jellyfin or another HLS
source on the signage loop rather than in the browser.

## Media upload

Reuses the existing authenticated control server. `POST /api/media/upload`,
`Content-Length` required, filename in the `X-Terebi-Filename` header.

**Why a header, not a query parameter.** A query value can be slurped into the
body reader by a misbehaving client, and it would put a filename in a URL, which
lands in logs and browser history. The header is read once, validated once, and
never echoed.

Write path: stream to `.upload-<uuid>.part` inside the media folder, then
atomically rename onto the validated name. A partial file is never visible to the
queue; the target name is claimed with `createNewFile` before the rename, so an
upload cannot overwrite a file an `adb push` put there — a duplicate is refused
with `409 duplicate_name`. Uploads are handled one at a time; a second concurrent
request is refused with `409 upload_in_progress`.

Bounds — reviewer-grade requirements, not aspirations:

| Rule | Value / behaviour |
| --- | --- |
| Per file | 256 MiB |
| Files | 200 |
| Total | 2 GiB |
| Free-space reserve | 256 MiB, checked **before** the write |
| Concurrency | 1 upload at a time (`409 upload_in_progress` beyond that) |
| Rate | 6/min per token |
| Missing `Content-Length` | `411 Length Required` |
| Oversize | `413 Payload Too Large` |
| Disk full / reserve / quota / file count | `507`, coded `disk_full` / `quota_exceeded` / `too_many_files`; partial file deleted |
| Bad type | `415 Unsupported Media Type` |

Validation of the content, in order:

1. Magic bytes must match the extension: `ftyp` for MP4, JPEG SOI for `.jpg`/
   `.jpeg`, PNG signature for `.png`. This is the real gate.
2. A `MediaMetadataRetriever` metadata check must also succeed — **best-effort
   container/metadata check**: it extracts metadata, it does not prove the
   decoder will play the file, and its behaviour on API 28 is unverified. It
   catches some truncated or mislabelled files that magic bytes alone accept. A
   file that passes it and still fails at playback is reported as `mediaError`
   and skipped.

Filenames:

- regex-bounded length and character set:
  `^[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}\.(mp4|jpg|jpeg|png)$`;
- **stored under the validated name** (`id = name`), with no manifest, so the
  folder stays manageable by `adb push` and a USB stick — see
  [Deviations accepted during implementation](#deviations-accepted-during-implementation);
- `..`, path separators, control characters, a leading dot and reserved names
  are refused with `400`;
- serve-back resolves the name to a direct child of the canonical media folder,
  refuses a symlink and re-checks the canonical parent, with
  `X-Content-Type-Options: nosniff`, `Content-Disposition: inline`, never
  `text/html`, and **no `Range` support** — so a video preview streams from the
  start.

Preview is capped at 32 MiB (constant, not a setting): a larger preview is a
download, and the controller is a phone.

**Blast radius.** A failed upload can leave at most one `.part` file of
bounded size, deleted on failure and swept at boot. It cannot damage an existing
library entry (the rename is atomic), cannot reach tokens or configuration
(media lives outside `filesDir`), and cannot exceed the quota, because quota and
reserve are checked before the first byte is written.

## Security and the trust boundary

The QMS page is third-party content, so the `@JavascriptInterface` sink is
**content-free**:

- one method;
- five fixed signal kinds;
- a sanitised value of **≤ 64 characters**;
- `void` return;
- 500 ms clamp;
- it never exposes a token, a URL or a command, in either direction.

The interface is registered **per-WebView**, not per-origin — per-origin
registration is not implementable without a race around navigation. The origin
gate therefore lives **inside the injected script**, and only the configured QMS
origin runs it.

**Why a content-free sink rather than per-origin registration.** If the interface
cannot be scoped to an origin at registration time, the only safe shape is one
that is worthless to any other origin: no readable state, no callable action, no
credential. Anything richer would need the per-origin guarantee the platform does
not give us here.

**The residual risk, stated plainly.** A hostile page that reaches the WebView
(which in V1 terms means any page the browser visits — there is still no URL
allowlist, see `docs/v1-scope.md`) could call the sink and force a mode flip.
The worst case is a **nuisance forced full-screen flip** on a clinic TV, not a
credential leak: the sink cannot read state, cannot navigate, cannot send a
command and cannot see the token. This is accepted and documented, in the same
spirit as V1's "the browser is the point of the app" note.

## Settings

All of these live in the controller's Media card and in
`app/src/main/java/com/terebibro/tv/config/ConfigStore.kt`. A `POST` is
validated as a whole: one bad value is `400 bad_request` naming the field, and
**nothing is written**.

All pixel values are authored against a 1080p surface and multiplied by
`renderScale` at runtime, where `renderScale = screenHeightDp / 1080` comes from
the dp the device reports, not from its physical pixels. They are settings in the
sense that they can be tuned, but their unit is "1080p pixels", not screen pixels.

| Key | Type | Default | Validation / notes |
| --- | --- | --- | --- |
| `mediaModeEnabled` | bool | `false` | Master switch; off means V1 behaviour. |
| `qmsUrl` | string | existing Home URL | `UrlValidator`; `http`/`https` only. A **blank** stored value resolves to the Home URL **at read time** and is never persisted as the substituted value, so it keeps tracking the Home URL. |
| `qmsStripSelector` | string | `main > div:nth-child(1)` | Non-blank, length-bounded, single selector; a comma or a CSS comment is refused (see [Structural selectors](#structural-selectors)). |
| `qmsNumberSelector` | string | `div:first-child > span` | Non-blank, length-bounded, same rule. |
| `qmsLocationSelector` | string | `div:nth-child(2) > span:first-child` | Non-blank, length-bounded, same rule. |
| `qmsTimeSelector` | string | `div:nth-child(2) > span:nth-child(2)` | Non-blank, length-bounded, same rule. |
| `callSource` | enum | `dom` | `dom` \| `dom_or_audio` \| `audio`. |
| `dwellSec` | int | `50` | 20–180. |
| `mediaSplitPercent` | int | `71` | 55–85. |
| `headerBandPercent` | int | `7` | 3–15; the three bands must sum to 100. |
| `stripHeightPercent` | int | `22` | 18–40. |
| `currentNumberPx` | int | `96` | 48–160. 1080p pixels. |
| `historyNumberPx` | int | `56` | 32–96. 1080p pixels. |
| `labelPx` | int | `15` | 10–24. 1080p pixels. |
| `currentCardPx` | int | `360` | 240–480. 1080p pixels. |
| `historyCardMinPx` | int | `190` | 120–320, must not exceed the max. 1080p pixels. |
| `historyCardMaxPx` | int | `320` | 190–480. 1080p pixels. |
| `uiLanguage` | enum | `ms` | `ms` \| `en` \| `zh` \| `ta`. |
| `mediaLocalEnabled` | bool | `true` | Local folder as a source. An ad-hoc URL overrides the queue without changing this switch — see [Ad-hoc URL](#ad-hoc-url). |
| `playlistEnabled` | bool | `false` | Off until a URL is configured. |
| `playlistUrl` | string | empty | `http`/`https` only. |
| `playlistRefreshSec` | int | `300` | 30–3600. |
| `imageDurationSec` | int | `10` | 3–120; applies to stills. |
| `volumePercent` | int | `100` | 0–100. |
| `mediaFillMode` | enum | `letterboxBlur` | `letterboxBlur` \| `letterbox` \| `cover`. |
| `newCallHighlight` | bool | `true` | 1.5 s pulse on the new card. |
| `uiScale` | int | `100` | 80–200, percent. Manual multiplier on `renderScale` for the **vendor page only**, applied with `setInitialScale`. Not applied to the media band or the strip. |

Hardcoded as constants, **not** settings, and documented here so that "why can't
I change it" has an answer:

- 500 ms signal clamp — a timing invariant; a configurable clamp is a bug
  generator.
- 15 s selector probe interval — detection health, not presentation.
- 30-minute `MEDIA_ONLY` hold — long enough to configure and preview; a mode
  that hides the queue must not outlive the job it was chosen for. `QMS_ONLY`
  holds indefinitely instead, because the queue stays visible.
- the upload caps (256 MiB / 200 files / 2 GiB / 256 MiB reserve / 3 concurrent /
  6 per minute);
- playlist 256 KiB / 200 items;
- 64-character signal value;
- 32 MiB preview limit.

## API surface

Existing envelope (`app/src/main/java/com/terebibro/tv/server/ApiResult.kt`):
`{"ok":true,…}` on success, `{"ok":false,"error":{"code":…,"message":…}}` on
failure. The check order is **unchanged and must stay unchanged**: peer →
route/method → Host → Origin → CSRF → framing → bearer token → rate limit →
strict JSON.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/settings/media` | Read the media settings block. |
| `POST` | `/api/settings/media` | Replace it as a whole; `400` names the bad field. |
| `POST` | `/api/media/mode` | Manual mode (`qms_only`, `media_only`, `split`); `media_only` holds 30 min, `qms_only` indefinitely, `split` drops any hold. |
| `POST` | `/api/media/open` | Play an ad-hoc URL now; `{"url": "..."}`, the same `UrlValidator` and `invalid_url` as `/api/nav/open`; overrides the queue until another source is chosen. |
| `GET` | `/api/media/files` | Library: `name`, `bytes`, `kind`, `modifiedMs`, plus totals and caps. |
| `POST` | `/api/media/upload` | Binary stream; `X-Terebi-Filename`; see [Media upload](#media-upload). |
| `POST` | `/api/media/delete` | Delete one file by `{"name": "..."}`. |
| `GET` | `/api/media/file` | Serve one stored file whole, named by `X-Terebi-Filename` (preview); no `Range`. |

`GET /api/settings/media` returns every writable key plus a **read-only
`runtime` object** holding `mode` and `renderScale`. `POST` accepts **only the
writable keys**: `runtime` is derived state, so a verbatim echo of the GET body —
including `runtime` — is rejected by the strict JSON reader rather than silently
ignored. Send the fields you mean to change, and nothing else.

State is **not** a new protocol. `BrowserState`
(`app/src/main/java/com/terebibro/tv/web/BrowserState.kt`), `StateJson`, the
WebSocket `state` broadcast and the retained MQTT `<base>/status` all gain the
following fields through the existing single fan-out:

`mediaEnabled`, `mediaMode`, `mediaSource`, `mediaItem`, `mediaIndex`,
`mediaCount`, `mediaPaused`, `mediaError`, `qmsNow`, `qmsLocation`,
`qmsDetectionHealthy`, `qmsLastCallAtMs`, `documentStartSupported`,
`mediaLocalFiles`, `mediaLocalBytes`, `mediaFreeBytes`, `uiLanguage`,
`screenWidthDp`, `screenHeightDp`, `densityDpi`, `renderScale`.

`mediaSource` is `"local"`, `"playlist"` or `"url"`, so a controller can say
which of the three sources is playing without inferring it from the item.

`renderScale` is a derived ratio (`screenHeightDp / 1080`), published so a
controller can check the factor without recomputing it. All of these fields are
published regardless of whether media mode is enabled.

**There is no `qmsNext`.** This QMS's left column is a call *history*, not a
queue, so there is no next number to report, and the strip never invents a queue
position. `qmsNow` is the newest call; the rest are `qmsLocation`-bearing
history entries in the strip.

MQTT additions (M4, additive only):

- actions `media-next`, `media-play`, `media-pause`, `set-mode` in
  `app/src/main/java/com/terebibro/tv/mqtt/MqttCommand.kt`;
- three Home Assistant **buttons** (next, play, pause) and two **sensors** (mode,
  now-serving call number) in `MqttDiscovery` — Mode is a sensor reading
  `mediaMode`, not a button;
- `<base>/status` inherits the new state fields unchanged.

An unknown or malformed action still produces the existing
`command/result` rejection; nothing new is introduced on that path.

## Phases

Four phases, each independently shippable, each with a demo that can be watched
on the reference TV.

| Phase | Scope | Demo |
| --- | --- | --- |
| **M1** Media layer, local playback, manual modes | Layout bands, `MediaPlayerHost`, local folder, split geometry, controller Media card, mode buttons. | Two clips pushed by `adb`, press SPLIT on a phone, 71/7/22 with video looping. |
| **M2** Call detection, dwell, live strip | Injected observer + fallback + probe, `QMS_FOCUS` + dwell, restyled strip with current/history, labels with the Malay (`ms`) dictionary, Back-ladder guard, diagnostics. | A real call flips full screen for 50 s and returns; a broken selector shows "unavailable" within 15 s. |
| **M3** Library and playlist | Upload/delete/list/preview, quota, playlist fetch + local fallback, controller library UI, language selector and the `en`/`zh`/`ta` dictionaries. | Upload an mp4 from a phone, it queues; unplug the network, local files keep playing; switch the labels to Tamil. |
| **M4** Chime, integrations, docs | Audio hook, audio-focus matrix, MQTT actions + HA entities, soak fixes, README updates. | `callSource=dom_or_audio` flips on the chime. |

Nothing in M2 depends on M3. M2 ships the labels in Malay, the default, which
needs no human review; `en`/`zh`/`ta` are machine-translated (see
[Extension: signage language selector](#extension-signage-language-selector)) and
arrive in M3, and reading them once with a staff member is cheap enough that it
should not be skipped.

## Risks

Ranked. Each has either a mitigation or the experiment that settles it.

| # | Risk | Mitigation / experiment |
| --- | --- | --- |
| 1 | React re-render wipes the strip layout. | Stylesheet scoped on `html[data-terebi-strip]` (React does not own `<html>`), re-asserted by the observer; probe plus native-card fallback. |
| 2 | 1080p H.264 decode alongside a live WebView on an Android 9 box. | Cap the envelope at 1080p30 H.264/AAC-LC, `SurfaceView`, no preload, release on destroy; 30-minute soak with `dumpsys gfxinfo` and `dumpsys media.player`. |
| 3 | Chromium 66 has no document-start script. | Feature-detect, dual-injection fallback, `documentStartSupported` in diagnostics, `callSource` default `dom`. |
| 4 | Selector rot on a third-party page. | Probe on every load, `qmsDetectionHealthy`, selector-level native card (`SPLIT` after one match, `QMS_ONLY` full screen before the first), page-level fallback to `MEDIA_ONLY`; never a stale number, and never a wrong guess at the activation form. |
| 5 | Storage exhaustion breaking token writes. | Quota, reserve, media outside `filesDir`, `.part` sweep at boot. |
| 6 | CJK/Tamil glyph coverage. | Device check; subset font or English fallback, decided after the check. |
| 7 | Trust boundary on the JS bridge. | Content-free sink, origin gate, 500 ms clamp; residual nuisance flip accepted and documented. |
| 8 | Upload path accidentally reusing the JSON body reader. | Explicit `BodyKind.Binary`, filename in a header, `.part` + rename, bounded concurrency. |
| 9 | D-pad/Back reaching the invisible QMS WebView in media modes. | `MediaKeyGuard` + explicit focus routing + `BackOrder.browserHidden`. |
| 10 | Audio focus conflicts (HDMI switch, Bluetooth). | Focus request/abandon/loss matrix, `resumeOnFocusGain` separate from the call path. |
| 11 | Playlist shape drift, or unreachable at boot. | Strict validation, last-good cache, local fallback. |
| 12 | Split math and strip typography at 720p vs 1080p. | Pure `MediaLayout.split`, integer rounding, parts always sum. |
| 13 | A device reporting native dp at 4K (3840×2160 at 160 dpi). | `renderScale = screenHeightDp / 1080` handles it automatically (`2.0`); `uiScale` corrects the vendor page if its CSS viewport still looks wrong. Settled by reading `densityDpi`, the dp pair and `renderScale` from `/api/device` on the actual TV before tuning anything. |

## Test plan

Pure-logic JVM units in the repo's house style — JUnit 4, backtick test names, no
mocks; the decision is extracted into an object and tested exhaustively:

- `MediaStateMachineTest` — every transition in [Modes](#modes), including
  re-arm, the 30-minute `MEDIA_ONLY` hold and the indefinite `QMS_ONLY` hold, the
  [boot rule](#boot-and-activation) (never matched keeps `QMS_ONLY`; a lost match
  after one match stays `SPLIT`), a call during a manual hold returning to that
  hold or to `SPLIT`, the page-level failure path (`SPLIT`→`MEDIA_ONLY`, and
  `QMS_ONLY`→`MEDIA_ONLY` at launch) and the selector-level failure path (stays in
  `SPLIT`, no transition).
- `MediaSettingsValidatorTest` — every range, every enum, the band-sum rule,
  and "one bad field writes nothing".
- `MediaLayoutTest` — 1080p, 720p at both 540 dp and 720 dp, 3840×2160 dp and a
  non-16:9 aspect; `renderScale` derived from reported dp, the single bottom
  arrangement, integer rounding, parts always sum exactly.
- `MediaFileRulesTest` — extension/magic-byte pairing, filename refusals (`..`,
  separators, control characters, leading dot, reserved names).
- `MediaPlaylistTest` — strict parsing, item cap, size cap, scheme refusal,
  last-good fallback ordering.
- `MediaQueueTest` — next/advance/loop, single item, empty source, and an
  ad-hoc URL as a one-item queue that loops.
- `QmsCallKeyTest` — the `number | location | time` key, whitespace
  normalisation, the re-call case (`A1001 → Kaunter 1 → A1001 → Kaunter 3` fires
  twice), and re-render-vs-call.
- `QmsSignalGuardTest` — the 500 ms clamp, including observer/interval
  coincidence.
- `QmsScriptTest` — selector and string escaping, CSS custom-property emission,
  idempotent install.
- `SignageStringsTest` — dictionary completeness for every shipped language
  (Malay in M2, all four in M3), no blanks, no missing or extra keys. It proves
  the dictionary is complete, not that the translation is right; no test does
  that, which is why one staff read of the five strings is the real gate.
- `MediaKeyGuardTest` — which keys are consumed in which mode.
- `BodyFramingTest` additions — the binary path beside the untouched JSON path.
- `StateJsonMediaTest` — the new fields, including the absence of `qmsNext`.
- `BackOrderTest` additions — `browserHidden` rung placement.
- `MqttCommandParserTest` additions — the four new actions and their
  rejections.

Then a **device checklist that cannot be automated**, on the reference Android 9
/ Chromium 66 unit:

1. V1 unchanged with media mode off: same Back ladder, overlays, pairing, MQTT.
2. 71/7/22 geometry at 1080p and at 720p, no seam and no gap between bands.
3. Strip text legible at 3 m.
4. A real call flips full screen for exactly `dwellSec`.
5. Two calls 3 s apart re-arm the timer without flicker or re-layout.
6. **The re-call case**: same number, different location, fires again.
7. Chime audible during focus (media paused, not muted).
8. Chime-only detection on the fallback injection path.
9. A broken selector degrades within 15 s and recovers when fixed; a page that
   will not load falls back to `MEDIA_ONLY`. A cold launch stays `QMS_ONLY` full
   screen through the daily activation form and reaches `SPLIT` only after it.
10. Network loss keeps local media playing.
11. Renderer crash recovers and re-injects the script, returning to `QMS_ONLY`
    full screen until the dashboard renders again.
12. Overlays pause media and restore focus on close.
13. Back ladder in all four modes.
14. D-pad does not activate invisible QMS controls in media modes.
15. Upload of a large file succeeds and plays; wrong magic bytes → `415`;
    `..` in the filename → `400`; a duplicate name → `409`; quota → `507` with
    pairing still working after a reboot.
16. 24 h soak: flat memory, stable decoder count.
17. CJK and Tamil labels render without tofu, at 4K as well as 1080p.
18. After a kill/relaunch the app is back at `QMS_ONLY` full screen — never
    straight into a split — and reaches `SPLIT` again once the dashboard
    renders.
19. On a native-dp 4K unit, geometry is the 1080p design at 2× with no seam or
    gap; on a density-scaled 4K unit it is the 1080p design at 1× and still
    sharp. Text is larger, not more numerous.
20. `GET /api/device` reports the `screenWidthDp` / `densityDpi` pair expected
    for the TV in use, and `renderScale` follows from it.

## Deliberately deferred

- **HLS / DASH / HEVC / AV1.** The framework decoder path is H.264/AAC in MP4,
  plus JPEG and PNG stills, and that is what signage needs. See
  [HLS, HEVC and Jellyfin](#hls-hevc-and-jellyfin) for what each one would
  actually require and why none of it is a dependency swap.
- **`stripPosition = "right"`.** A right-hand strip column was specified and
  validated but never implemented; the setting was removed rather than published
  as a switch with no effect. The bottom arrangement is the only one.
- **`Range` / seek in preview.** Previews are short; seeking is a controller
  feature nobody has asked for.
- **Thumbnails.** The controller lists names and sizes, not frames.
- **Step-shift and marquee motion.** See [No motion](#no-motion).
- **A true lower-third overlay.** It needs `TextureView` and its measured cost.
- **Scheduling / day-parting.** No time-of-day rules for playlists.
- **Boot auto-start.** Unchanged from V1 (`docs/v1-scope.md`).
- **Translating the controller UI or Android system strings.** Staff-facing and
  OS-owned respectively.
- **Per-item playlist scheduling beyond `durationSec`.** No per-item windows.
- **A second WebView.** One WebView is a design constraint, not an oversight.

## Deviations accepted during implementation

Five places where the build settled differently from the scope above. Each is
recorded here because this document is what someone reads before they trust the
code.

- **Media is stored under the validated filename** (`id = name`), not under a
  hash with a manifest, so the folder stays usable by hand and both `adb push`
  and a USB stick keep working. Traversal safety comes from the anchored
  filename rule plus the canonical-path and symlink checks, and a duplicate name
  is refused rather than overwritten.
- **`stripPosition = "right"` was removed** rather than shipped: it was
  validated and published but never implemented, and a setting that does nothing
  is worse than no setting. The bottom arrangement is the only one; see
  [Deliberately deferred](#deliberately-deferred).
- **`callSource = "audio"` means the DOM updates the text but does not take
  over** — only the chime moves the screen. The earlier text implied all three
  values were equivalent in that respect; they are not.
- **A chime landing within 1 s of a DOM call is treated as the same
  announcement** and does not re-arm the dwell, because the observer and the
  chime are describing one call. A chime a second or more later is its own
  signal.
- **The preview route has no `Range` support**, so a video preview streams from
  the start. Seeking in a preview was never in scope.

## Verification

V2 is implemented across six stacked branches, one per milestone: **M1a**
(settings, reducer and control API), **M1b** (view layer, player and on-device
controls), **M2** (detection, strip and boot rule), **M3a** (library, upload and
preview), **M3b** (playlist and four languages) and **M4** (chime, MQTT and this
document). Each milestone was validated when it was written, by building and by
JVM unit tests only:

| Milestone | JVM unit tests | `lintDebug` | `assembleDebug` |
| --- | --- | --- | --- |
| **M1a** | passed | 0 errors | `app-debug.apk` built |
| **M1b** | passed | 0 errors | `app-debug.apk` built |
| **M2** | passed | 0 errors | `app-debug.apk` built |
| **M3a** | passed | 0 errors | `app-debug.apk` built |
| **M3b** | passed | 0 errors | `app-debug.apk` built |
| **M4** | passed | 0 errors | `app-debug.apk` built |

The suite is cumulative, so each milestone's count is a step on the way to the
final figure: **542 tests, 0 failures, 1 skipped**.

What the JVM tests cover, beyond the V1 suite: the media reducer and layout, the
settings validator, the QMS call key, signal clamp and injected-script escaping,
the media queue and playlist parser, the upload rules (filename, magic bytes,
quota arithmetic and the duplicate refusal), the media state JSON, the strict
MQTT parser with the four new actions and their key sets, the discovery builder
with the three new buttons and two new sensors (each button payload re-parsed
through the real command parser), the `command/result` shape — an existing action
still reports exactly `{"id":…,"ok":true}`, and a disabled media action adds only
`applied`/`reason` — and the chime rule (source gating, the 1 s DOM-call
debounce, and the `audio` source suppressing the DOM takeover while still
updating the strip text).

`lintDebug` runs with **0 errors**. Its 34 warnings are pre-existing: `setText`
literals and dependency-version notices in `MainActivity`, plus manifest and
resource warnings. None is in the media or MQTT code.

**The single skip is the symlink-refusal test.** A Windows host without Developer
Mode cannot create a symlink, so the test `Assume`s its way out instead of
running. The refusal itself *is* implemented — `MediaPaths` checks
`Files.isSymbolicLink` and re-checks the canonical parent before any serve-back —
but the test that proves it has not executed on this host.

**Nothing was verified on a device.** The injected script has never executed
against the real QMS page, so selector matching, the strip restyle, the boot rule
across the daily activation, the CSS on Chromium 66, the upload path, the
playlist fetch, local and remote playback, the chime, the MQTT actions and the
Home Assistant entities have not been exercised at runtime. Route-level behaviour
has no server harness in this repository: no route has been driven end to end,
only its pure helpers. The media decoder running alongside a live WebView, 720p
geometry and the 24 h soak remain open.

A first device pass should check three things, in this order:

1. **Does the strip render** — do the four selectors match the live page, and does
   the restyled band show the vendor's own number, location and time?
2. **Does the boot rule hold across the daily activation** — a cold start stays
   full screen through the code form and reaches `SPLIT` only once the dashboard
   is rendering, and again after a renderer crash or a refresh?
3. **Does an upload from a phone work end to end** — filename accepted, magic
   bytes and metadata accepted, the file listed, and the clip or still actually
   playing in the band?

What this does not cover: there is **no CI, no automated device test suite**, and
one API 28 unit is not a hardware matrix. The two V2-specific gaps a single unit
cannot close are 720p geometry and the media decoder under a 24 h soak. Treat V2
as **unverified at runtime** until a device pass is recorded here.
