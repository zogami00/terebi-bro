# Android TV Fullscreen Browser — Implementation Plan

## 1. Goal

Build a lightweight Android TV application that acts as a fullscreen web browser and can be managed from any browser on the same local network.

The solution should require:

- No cloud service
- No separate control application
- No external server
- No user account
- No dependency on Google Play Services
- Android 11 or newer

The Android TV application itself will provide:

1. Fullscreen WebView browser
2. Embedded local web server
3. Browser-based administration page
4. mDNS network discovery
5. Android TV remote control support

---

# 2. High-Level Architecture

```text
                     Local Network

       Phone / Tablet / Laptop / Desktop
                       │
                       │ HTTP / WebSocket
                       │
          http://display-tv.local
                 or TV IP address
                       │
                       ▼
┌─────────────────────────────────────────────┐
│              Android TV App                 │
│                                             │
│   ┌─────────────────────────────────────┐   │
│   │ Embedded HTTP/WebSocket Server      │   │
│   │                                     │   │
│   │ • Web controller                    │   │
│   │ • REST API                          │   │
│   │ • Live status                       │   │
│   └─────────────────┬───────────────────┘   │
│                     │                       │
│                     ▼                       │
│   ┌─────────────────────────────────────┐   │
│   │ Browser Controller                  │   │
│   │                                     │   │
│   │ • Navigation                        │   │
│   │ • Settings                          │   │
│   │ • Remote commands                   │   │
│   └─────────────────┬───────────────────┘   │
│                     │                       │
│                     ▼                       │
│   ┌─────────────────────────────────────┐   │
│   │ Android System WebView              │   │
│   │                                     │   │
│   │ Fullscreen displayed website        │   │
│   └─────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

---

# 3. Platform Requirements

## Android

Minimum:

```text
Android 11
API 30
```

Recommended project configuration:

```text
minSdk = 30
compileSdk = current stable Android SDK
targetSdk = current supported target SDK
```

The app should work on:

- Android TV
- Google TV
- Android-based commercial displays
- Industrial Android devices
- Android TV boxes

Touchscreen support may exist, but the primary interaction model should remain compatible with a standard TV remote.

---

# 4. WebView Strategy

Use:

```text
android.webkit.WebView
```

with AndroidX WebKit where appropriate.

Do not:

- Bundle Chromium
- Bundle Gecko
- Pin a browser engine
- Ship a custom WebView implementation

The application should use whatever WebView provider is installed on the Android device.

Advantages:

- Smaller APK
- System WebView security updates
- Better long-term maintainability
- No browser-engine maintenance burden

The application should detect and expose:

- Android version
- WebView provider
- WebView version
- App version

The controller should warn when the installed WebView appears unusually old, but it should not unnecessarily prevent the application from running.

---

# 5. Browser Mode

The primary application screen consists almost entirely of the WebView.

Default behavior:

```text
Launch App
   ↓
Load configured Home URL
   ↓
Enter immersive fullscreen
   ↓
Keep display awake
```

Hide:

- Android status bar
- Navigation bar where supported
- App toolbar
- Browser address bar
- Browser buttons

The result should appear like a dedicated application rather than a browser.

---

# 6. WebView Capabilities

Enable support for typical modern web applications:

- JavaScript
- DOM storage
- LocalStorage
- SessionStorage
- Cookies
- Third-party cookies where explicitly required
- WebSockets
- HTTPS
- Audio/video playback
- HTML5 fullscreen video
- File downloads where required
- Browser history
- Back/forward navigation
- Responsive sites
- Authentication sessions

Do not bypass TLS certificate errors automatically.

Invalid certificates should remain blocked unless a deliberate future configuration option is introduced.

---

# 7. Home URL

Maintain two concepts:

## Home URL

Permanent configured startup URL.

Example:

```text
https://dashboard.example.local/
```

## Current URL

Whatever page the browser is currently displaying.

Example:

```text
https://dashboard.example.local/status/room1
```

Commands should include:

```text
Open URL
Set Home URL
Open URL + Set as Home
Go Home
```

The Home URL should persist across:

- App restarts
- Device restarts
- Power loss

---

# 8. Startup Behaviour

When the app starts:

1. Load persistent configuration.
2. Start embedded control server.
3. Start mDNS advertisement.
4. Initialise WebView.
5. Load configured Home URL.
6. Enter fullscreen mode.
7. Keep display awake if enabled.

Optional later:

```text
Launch automatically after Android boots
```

This should be configurable.

---

# 9. Embedded Web Server

The Android application should run a small local HTTP server.

Example:

```text
http://192.168.1.50:8765
```

The server provides:

```text
/
    Web controller

/api/*
    Browser control API

/ws
    Live WebSocket connection
```

The HTTP port should be configurable, with a sensible default such as:

```text
8765
```

---

# 10. mDNS Discovery

Advertise the controller through mDNS.

Example hostname:

```text
display-tv.local
```

Device names should be configurable.

Examples:

```text
reception-tv.local
waiting-room-tv.local
meeting-room-tv.local
```

IP access must remain available because some networks block multicast or mDNS.

The local settings screen should therefore display both:

```text
Controller:
http://waiting-room-tv.local:8765

IP:
http://192.168.1.84:8765
```

---

# 11. Web Controller

The controller should be responsive and suitable for:

- Phones
- Tablets
- PCs
- Laptops

No external web assets should be required.

HTML, CSS and JavaScript should be bundled inside the APK.

Basic controller structure:

```text
TV Browser

Status
─────────────────────────
● Online
Waiting Room TV

Current URL
https://example.local/dashboard

URL
[                                  ]

[ Open ]
[ Open + Set Home ]

Navigation
[ Back ] [ Forward ]
[ Home ] [ Reload ]
[ Stop ]

Remote
          [ ↑ ]

     [ ← ][ OK ][ → ]

          [ ↓ ]

[ Back ] [ Reload ]

Browser
[ Restart WebView ]
[ Clear Cache ]
[ Clear Site Data ]

Settings
Home URL
Device Name
Fullscreen
Keep Screen Awake

Device
Android Version
WebView Version
App Version
IP Address
Uptime
```

---

# 12. Live Controller Updates

Use WebSocket communication between the controller webpage and Android app.

The page should update automatically when:

- Current URL changes
- Page begins loading
- Page finishes loading
- Browser encounters an error
- Home URL changes
- Network state changes
- Settings change

No manual controller refresh should normally be necessary.

---

# 13. Browser Controls

Initial API/control set:

### Navigation

```text
Open URL
Back
Forward
Home
Reload
Stop loading
```

### Browser management

```text
Restart WebView
Clear cache
Clear cookies/site data
```

### Display

```text
Fullscreen ON/OFF
Keep screen awake ON/OFF
```

### Configuration

```text
Set Home URL
Set Device Name
Set Controller Port
```

### Status

```text
Current URL
Page title
Loading state
WebView version
App version
Android version
IP address
Uptime
Network status
```

---

# 14. TV Remote Support

A normal Android TV remote must be sufficient to operate the displayed website.

Required keys:

```text
↑
↓
←
→
OK / Select
Back
```

WebView focus behaviour should be tuned for TV navigation.

Where a website supports keyboard/focus navigation naturally, the app should allow the WebView to handle these events normally.

---

# 15. Local Browser Menu

Provide a hidden or unobtrusive native browser menu.

Suggested trigger:

```text
Long press Back
```

Alternative if the device provides one:

```text
Menu button
```

Menu:

```text
Browser

Home
Reload
Enter URL
Settings
Device Information
Exit App
```

This allows the TV to remain manageable even if no phone or computer is available.

---

# 16. Enter URL From TV

The native menu should offer:

```text
Enter URL
```

Selecting it opens:

- URL text field
- Android TV software keyboard

Actions:

```text
Open
Open + Set as Home
Cancel
```

This is primarily a fallback because URL entry will usually be easier through the web controller.

---

# 17. Web Remote Control

The controller webpage should also provide a virtual D-pad:

```text
          ↑

      ←   OK   →

          ↓

     BACK
```

These actions should control navigation within the application's WebView where feasible.

This feature controls the browser application, not Android globally.

System-wide remote-control functionality is outside the initial scope.

---

# 18. Security

Even on a LAN, control access should not be completely unauthenticated.

Initial recommended system:

### First-run pairing

TV displays:

```text
Pairing PIN: 482931
```

User opens:

```text
http://display-tv.local
```

and enters the PIN.

After successful pairing, the browser receives a persistent authentication token.

Future visits from the same browser remain authenticated.

---

# 19. Security Controls

Support:

- Random first-run pairing PIN
- Persistent controller token
- Regenerate/revoke controller tokens
- Reset paired devices from TV
- Optional administrator password
- CSRF protection for controller API
- Local-network binding only
- Request validation
- URL validation

Do not expose the management server externally by default.

---

# 20. URL Restrictions

Support two operating modes.

## Open mode

```text
Allow arbitrary URLs: ON
```

Useful for general-purpose browser installations.

## Restricted mode

Example:

```text
Allowed URLs:

*.company.local
dashboard.example.com
192.168.20.*
```

Requests outside the allowlist should be rejected.

This should be configurable through the controller.

---

# 21. Network Failure Handling

If the webpage becomes unavailable:

```text
Website unavailable

Retrying...

[ Retry Now ]
[ Home ]
```

Configurable retry behaviour:

```text
Automatic retry: ON
Retry interval: 10 seconds
```

When connectivity returns, the page should automatically recover.

The local controller must remain operational even when internet access is unavailable.

---

# 22. WebView Crash Recovery

Handle WebView renderer termination.

Expected behaviour:

```text
Renderer crashes
       ↓
Destroy failed WebView
       ↓
Create new WebView
       ↓
Restore current URL
```

The user should not have to restart the Android application manually.

---

# 23. Persistent Configuration

Store configuration locally.

Suggested settings:

```text
deviceName
homeUrl
controllerPort
fullscreen
keepScreenAwake
autoStart
autoRetry
retryInterval
allowArbitraryUrls
allowedDomains
pairingSettings
```

Android DataStore would be suitable.

No database is required initially.

---

# 24. Device Information Screen

Expose useful diagnostics:

```text
Device Name
Android Version
SDK Level
Device Manufacturer
Device Model

App Version
Build Number

WebView Provider
WebView Version

IP Address
mDNS Name
Controller Port

Home URL
Current URL

App Uptime
```

This should be accessible both:

- On the TV
- Through the web controller

---

# 25. Logging

Maintain lightweight local logs.

Examples:

```text
App started
WebView started
URL opened
Home URL changed
Network disconnected
Network restored
Page load failed
WebView renderer restarted
Controller paired
Controller request rejected
```

The web controller should provide:

```text
View Logs
Download Logs
Clear Logs
```

Avoid logging:

- Passwords
- Cookies
- Authentication headers
- Form contents
- Controller tokens

---

# 26. Project Structure

Suggested architecture:

```text
app/
├── browser/
│   ├── BrowserActivity
│   ├── BrowserManager
│   ├── WebViewFactory
│   └── BrowserState
│
├── server/
│   ├── LocalServer
│   ├── ApiRoutes
│   ├── WebSocketManager
│   └── Authentication
│
├── discovery/
│   └── MdnsService
│
├── settings/
│   ├── SettingsRepository
│   └── SettingsScreen
│
├── remote/
│   └── RemoteCommandHandler
│
├── diagnostics/
│   ├── DeviceInfo
│   └── LogRepository
│
└── assets/
    └── controller/
        ├── index.html
        ├── app.js
        └── app.css
```

---

# 27. Implementation Phases

## Phase 1 — Core Fullscreen Browser

Build:

- Android TV project
- Android 11 minimum
- System WebView
- Configurable Home URL
- Immersive fullscreen
- Keep screen awake
- D-pad navigation
- Back navigation
- Persistent settings

Success criteria:

> APK launches directly into a usable fullscreen website on Android TV.

---

## Phase 2 — Embedded Controller

Build:

- Embedded HTTP server
- Bundled controller webpage
- Current URL/status
- Open URL
- Home
- Back
- Forward
- Reload
- Stop

Success criteria:

> A phone on the same LAN can control the TV browser using only its browser.

---

## Phase 3 — Discovery

Add:

- mDNS advertisement
- Configurable device name
- `.local` hostname
- IP fallback information

Success criteria:

> User can access the controller without manually discovering the TV's IP on compatible networks.

---

## Phase 4 — Live Communication

Add:

- WebSocket connection
- Real-time browser state
- Live current URL
- Loading state
- Online/offline state

Success criteria:

> Controller immediately reflects what is happening on the TV.

---

## Phase 5 — TV Controls

Add:

- Long-press Back menu
- Native settings
- URL entry
- Device information
- Restart WebView
- Remote D-pad compatibility improvements

Success criteria:

> The app remains manageable with only the physical TV remote.

---

## Phase 6 — Security

Add:

- Pairing PIN
- Controller token
- Token reset
- URL allowlist
- API validation
- Controller authentication

Success criteria:

> Other devices on the LAN cannot silently take control without being paired.

---

## Phase 7 — Reliability

Add:

- Network retry
- WebView crash recovery
- App state restoration
- Boot recovery
- Error screen
- Connection diagnostics
- Local logs

Success criteria:

> The app can run unattended for extended periods and recover from common failures.

---

## Phase 8 — Optional Kiosk Features

Potential later features:

- Auto-start after device boot
- Android Lock Task / Device Owner support
- Prevent accidental app exit
- Scheduled page reload
- Scheduled URL changes
- Screen on/off schedules
- Brightness management
- Screenshot of current WebView
- Multiple saved URLs/bookmarks
- Controller themes
- QR code displaying controller address
- Configuration export/import
- OTA app update support
- Fleet/server integration

These should remain separate from the basic fullscreen-browser architecture.

---

# 28. First-Run Experience

Suggested setup flow:

```text
Install APK
    ↓
Launch
    ↓
Enter Home URL
    ↓
Choose Device Name
    ↓
Save
    ↓
Browser launches fullscreen
```

Temporary setup overlay:

```text
Waiting Room TV

Controller:
http://waiting-room-tv.local:8765

IP:
http://192.168.1.84:8765

Pairing PIN:
482931

[ Start Browser ]
```

Once configured, normal startup should skip this screen.

---

# 29. Recommended V1 Scope

The first production-worthy release should include:

- Android 11+
- Android System WebView
- Fullscreen immersive browser
- Persistent Home URL
- Android TV D-pad support
- Embedded HTTP server
- Responsive web controller
- Open URL
- Set Home URL
- Home
- Back
- Forward
- Reload
- Stop
- Restart WebView
- Clear cache
- Clear cookies/site data
- Device name
- mDNS discovery
- IP fallback
- Live WebSocket status
- Keep-screen-awake
- Automatic network retry
- WebView crash recovery
- Pairing PIN
- Controller authentication
- Device/WebView diagnostics

No external service should be required.

---

# 30. V1 Design Principle

The application should follow one central principle:

> **Install one APK, connect the Android TV to the LAN, and manage the fullscreen browser from any normal web browser on that network.**

There should be no requirement for:

- Cloud hosting
- Separate controller software
- User registration
- Dedicated backend server
- Proprietary browser engine

The Android TV device remains completely self-contained.