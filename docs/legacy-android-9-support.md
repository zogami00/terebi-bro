# Legacy Android 9 (API 28) support

A practical runbook for running Terebi Bro on old Android TV hardware, and for
replacing an ancient system WebView where the ROM allows it. The commands and
results below were observed on **one API 28 reference unit** by the maintainer;
your hardware is not the same, so treat every value here as a starting point and
verify what you depend on. Where something was **not** verified on hardware it
is marked as such (see [Honest limitations](#10-honest-limitations)).

## 1. What "legacy support" means here

The specification targets **Android 11 or newer (API 30)**. Terebi Bro lowers
`minSdk` to **28 (Android 9)** as a deliberate, documented compatibility
fallback for older TV hardware (`app/build.gradle.kts`; see also
`docs/v1-scope.md` and the README's
[Supported Android versions](../README.md#supported-android-versions)).

- **API 30+ is the target.** Behaviour there is unchanged and untouched by the
  fallback.
- **API 28–29 is supported but is *not* the target.** Only **API 28 was verified
  on hardware**; **API 29 is supported by the code but is hardware-unverified**.
  On API 28 the app installs, launches and runs, with the older
  immersive-fullscreen mechanism (`systemUiVisibility` flags instead of
  `WindowInsetsController`).
- **The WebView is almost always the limiting factor, not the app.** These
  devices usually ship a system WebView years behind Chromium 100, so a modern
  page can render as unstyled HTML. That is a device problem; the app does not
  and cannot fix it, only warn about it.

`compileSdk` and `targetSdk` stay 36. Only `minSdk` is lowered.

## 2. The reference device

The work in this document was performed on one concrete unit. Recording it here
gives the runbook context — a second unit will differ.

| Property | Value |
| --- | --- |
| Reported model | `onn STICK` |
| Reported manufacturer | `Hisilicon` |
| Platform | `bigfish` |
| `ro.build.version.sdk` | `28` |
| `ro.build.version.release` | `15.0` (**spoofed**) |
| Security patch | `2019-03-05` |
| Build fingerprint | `google/walley/eros-p1:10/D9PRO5G/eng.rom.20260319.140934:userdebug/test-keys` |
| ABI | `armeabi-v7a` (32-bit ARM) |

`adb root` works on this unit because the ROM is **`userdebug` + `test-keys`**.

> **Treat this device with suspicion.** The version strings are internally
> inconsistent and demonstrably spoofed — it reports platform `10` in the
> fingerprint while `release` claims `15.0`, and the model/manufacturer do not
> correspond to the hardware. This is a rebadged / counterfeit TV stick. Do not
> trust any property it reports; verify each one you depend on.

## 3. Diagnosing an old WebView

Connect and establish what is actually installed and, more importantly, which
provider is **active**:

```powershell
adb connect <device-ip>:5555
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
adb shell dumpsys webviewupdate
adb shell pm list packages -f | grep -i webview
adb shell dumpsys package com.google.android.webview | grep -E 'versionName|targetSdk|minSdk'
```

What each tells you:

| Command | What it answers |
| --- | --- |
| `getprop ro.build.version.sdk` | The real API level (`28` on the reference unit), regardless of the spoofed `release` string. |
| `getprop ro.product.cpu.abilist` | Which ABI any replacement WebView APK must match (`armeabi-v7a` here). |
| `dumpsys webviewupdate` | The authoritative view: which package is **Current** / **Preferred**, and which are valid alternatives. |
| `pm list packages -f \| grep -i webview` | Every WebView package present on disk, with its APK path. |
| `dumpsys package com.google.android.webview \| grep -E 'versionName\|targetSdk\|minSdk'` | The version and SDK levels of that specific package. |

**The active provider is what matters.** A newer WebView can be installed and
still be unusable: on Android 7–9 the framework exposes only the providers
listed in `framework-res.apk`, so a package that exists on disk but is not on
that list shows up as an installed-but-unselectable candidate and is never used.
A Chromium that is newer on paper but cannot be selected changes nothing.

## 4. Why you cannot just install a newer WebView

On Android 7–9 the framework permits only the providers named in
`framework-res.apk` → `res/xml/config_webview_packages.xml`. Setting a provider
that is not on that list is accepted by the settings command but **silently
ignored**:

```powershell
adb shell settings put global webview_provider com.google.android.webview
adb shell dumpsys webviewupdate   # if the package is not listed, nothing changed
```

There is also **no off-the-shelf modern WebView packaged as
`com.android.webview` for 32-bit ARM**:

| Project | Package name |
| --- | --- |
| Bromite | `org.bromite.webview` |
| Mulch | `us.spotco.mulch_wv` |
| Google | `com.google.android.webview` |
| Cromite | ships **no** 32-bit ARM SystemWebView at all |

So "download a modern build and swap it in under the same name" is **not
available** — there is no such build to install. If a modern provider is already
installed on the device, the viable route is to **whitelist it** in the
framework's config file (below), which makes the framework allow selecting it.
This is a ROM modification, not an app install.

## 5. Pre-flight checks and backups (do these first)

Work through these before touching the framework. On the reference device
`adb root` succeeded because the ROM is `userdebug` + `test-keys`.

> **⚠️ Warning — ROM modification, boot risk.** Writing a modified
> `framework-res.apk` is a **ROM modification, performed entirely at your own
> risk**. A bad `framework-res.apk` can prevent the device from booting. This is
> not an app install, and it is not undone by uninstalling anything. **Do not
> proceed past the backup** until (a) the backup exists and its hash matches the
> device, and (b) you have read and understood the recovery procedure in step 4
> — including that it depends on `adbd` starting (see step 4).

1. **Gain root and confirm it.**

   ```powershell
   adb root
   adb shell id
   ```

   `id` must report `uid=0`.

2. **Back up the framework and record its hash.** Keep the backup **outside the
   repository**, and verify it before trusting it:

   ```powershell
   adb pull /system/framework/framework-res.apk <backup-path>
   Get-FileHash <backup-path> -Algorithm SHA256
   adb shell sha256sum /system/framework/framework-res.apk
   ```

   The local `Get-FileHash` value and the device `sha256sum` value **must
   match**. If they differ, the pull is corrupt — do not rely on it; pull again.
   Keep it somewhere you can reach if the device bootloops. Never commit it.

3. **Confirm you can remount.** On Android 9 with system-as-root the writable
   target is **`/`**, not `/system`:

   ```powershell
   adb shell "mount -o rw,remount /"
   ```

   Verify `/system/framework` is actually writable by touching a temp file, then
   remount read-only again:

   ```powershell
   adb shell "touch /system/framework/.terebi-write-test && rm /system/framework/.terebi-write-test"
   adb shell "mount -o ro,remount /"
   ```

4. **Know the recovery procedure before you need it.** If the device bootloops,
   `adbd` still starts from the ramdisk **if the ROM allows it**, so you may be
   able to recover over ADB. `adb root` does **not** survive a reboot or a
   bootloop, so re-establish it every time you reconnect:

   ```powershell
   adb connect <device-ip>:5555
   adb root
   adb shell id
   adb shell "mount -o rw,remount /"
   adb push <backup-path> /system/framework/framework-res.apk
   adb shell "chown root:root /system/framework/framework-res.apk"
   adb shell "chmod 644 /system/framework/framework-res.apk"
   adb shell sync
   adb reboot
   ```

   > **Network ADB note.** `adb root` over a TCP connection can drop the
   > connection. If `adb shell id` fails or reports a non-root uid, run
   > `adb connect <device-ip>:5555` again and retry `adb root`.

   `id` must report `uid=0` before the remount. **Recovery is not guaranteed:**
   it depends on `adbd` starting after the bootloop. If it does not start, you
   cannot recover over ADB and you will need an **offline flash** or a **vendor
   restore tool** for the device.

5. **Gate — do not proceed past this section until both are true.**
   - The backup from step 2 exists and its SHA-256 matches the device.
   - You have read and understood the recovery procedure in step 4, including
     that it is not guaranteed.

## 6. The patch procedure

You are adding one `<webviewprovider>` entry to `config_webview_packages.xml`
and re-injecting **only that file** into the original framework APK.

1. **Decode** the backed-up framework APK:

   ```powershell
   java -jar apktool.jar d -f -o decoded framework-res.apk
   ```

2. **Edit** `decoded/res/xml/config_webview_packages.xml`. Before:

   ```xml
   <webviewproviders>
       <webviewprovider availableByDefault="true" description="Android WebView" packageName="com.android.webview" />
   </webviewproviders>
   ```

   After — add the second entry and **keep the original**, so the old engine
   remains a selectable fallback:

   ```xml
   <webviewproviders>
       <webviewprovider availableByDefault="true" description="Android WebView" packageName="com.android.webview" />
       <webviewprovider availableByDefault="true" description="Android System WebView" packageName="com.google.android.webview" />
   </webviewproviders>
   ```

3. **Rebuild** with apktool:

   ```powershell
   java -jar apktool.jar b -f -o rebuilt.apk decoded
   ```

4. **Inject only that one file** into a copy of the *original* APK, so
   `resources.arsc` and every other entry stay byte-identical and resource IDs
   cannot shift.

   Do all of this in **one working directory** — call it `<patchdir>` (for
   example `C:\patch`), where the original `framework-res.apk` and the built
   `rebuilt.apk` already live. `patched.apk` is created there, the compiled XML
   is extracted to `<patchdir>\res\xml\`, and **`7z u` must run from inside
   `<patchdir>`** so the stored path `res\xml\config_webview_packages.xml`
   matches the entry in the archive:

   ```powershell
   cd <patchdir>                                            # e.g. C:\patch
   copy framework-res.apk patched.apk
   7z e rebuilt.apk res/xml/config_webview_packages.xml -o<patchdir>\res\xml -y
   7z u patched.apk res\xml\config_webview_packages.xml -tzip -y
   ```

   > **Do not do this with .NET's `System.IO.Compression.ZipArchive` in Update
   > mode.** It corrupted the archive on the reference device: it produced a
   > CRC/size mismatch on `AndroidManifest.xml` and `aapt2` could not open the
   > result. Use **7-Zip** (or an equivalent tool that preserves all other
   > entries untouched).

5. **Verify before pushing.** All of these must pass. The `build-tools`
   directory below is an **example** — the version (`36.0.0`) depends on which
   build-tools you have installed; substitute your actual directory.

   ```powershell
   7z t patched.apk

   # Reduce a `7z l -slt` listing to per-entry fields only.
   #
   # Do NOT compare the raw listings directly. `7z l -slt` mixes in
   # archive-level lines (`Path = <archive name>`, `Physical Size`, ...) and
   # a per-entry `Offset`; re-injecting a single file legitimately changes the
   # archive's own size and every following entry's offset, so a naive
   # `Compare-Object` of two raw listings reports many differences even on a
   # correct patch. Keep only Path/Size/CRC/Method, taken from the per-entry
   # section (everything after the first `----------` separator).
   function Get-Entries([string]$path) {
       $lines  = Get-Content $path
       $sep    = ($lines | Select-String -SimpleMatch '----------' |
                  Select-Object -First 1).LineNumber
       $result = @()
       $cur    = $null
       foreach ($line in $lines[$sep..($lines.Count - 1)]) {
           if ($line -match '^Path = (.*)$') {
               if ($null -ne $cur) { $result += $cur }
               $cur = [ordered]@{ Path = $Matches[1] }
           } elseif ($null -ne $cur -and $line -match '^(Size|CRC|Method) = (.*)$') {
               $cur[$Matches[1]] = $Matches[2]
           }
       }
       if ($null -ne $cur) { $result += $cur }
       $result
   }

   7z l -slt patched.apk       > patched.list.txt
   7z l -slt framework-res.apk > original.list.txt
   $orig    = Get-Entries original.list.txt
   $patched = Get-Entries patched.list.txt

   # Must show exactly one difference: res/xml/config_webview_packages.xml
   Compare-Object $orig $patched -Property Path, Size, CRC | Format-Table -AutoSize

   & "$env:ANDROID_HOME\build-tools\36.0.0\aapt2.exe" dump badging patched.apk
   & "$env:ANDROID_HOME\build-tools\36.0.0\aapt2.exe" dump xmltree patched.apk --file res/xml/config_webview_packages.xml
   ```

   - `7z t` must report the archive as **OK** — this catches a corrupted
     archive.
   - The two `7z l -slt` listings, once reduced to per-entry `Path`/`Size`/`CRC`
     records as above, must show the **only** `Path`/`Size`/`CRC` difference as
     `res/xml/config_webview_packages.xml` (its `Size` and `CRC` change). No
     other entry may differ, and no entry may be added or removed. (`Method` is
     captured for context but is **not** compared, because re-injecting the file
     may legitimately recompress it.) `Compare-Object` must therefore emit
     exactly one pair of rows for that single path.
   - `dump badging` must still report the package as **`android`** (the original
     framework package name).
   - `dump xmltree` must list **both** providers.
   - The entry count must be **identical to the original** — **3003** entries in
     the reference case — and `resources.arsc` must still be **`Stored`** with
     an **unchanged length**.

## 7. Deploy and verify

1. **Gain root and confirm it.** `adb root` does not survive a reboot or a
   bootloop, so re-establish it here instead of assuming it is still active:

   ```powershell
   adb root
   adb shell id
   ```

   > **Network ADB note.** `adb root` over a TCP connection can drop the
   > connection. If `adb shell id` fails or reports a non-root uid, run
   > `adb connect <device-ip>:5555` again and retry `adb root`.

   `id` must report `uid=0`.

2. **Remount read-write and push:**

   ```powershell
   adb shell "mount -o rw,remount /"
   adb push patched.apk /system/framework/framework-res.apk
   adb shell "chown root:root /system/framework/framework-res.apk"
   adb shell "chmod 644 /system/framework/framework-res.apk"
   adb shell sync
   ```

3. **Compare hashes** — the device copy must match the local file:

   ```powershell
   adb shell sha256sum /system/framework/framework-res.apk
   Get-FileHash patched.apk -Algorithm SHA256
   ```

4. **Remount read-only and reboot:**

   ```powershell
   adb shell "mount -o ro,remount /"
   adb reboot
   ```

5. **After boot**, network ADB came back on **5555 by itself** on the reference
   device, so:

   ```powershell
   adb connect <device-ip>:5555
   adb shell dumpsys webviewupdate
   ```

   `dumpsys webviewupdate` must show the new package as **Current** and
   **Preferred**, with the old `com.android.webview` still listed as a valid
   alternative. This is a read-only check; `adb root` does **not** survive the
   reboot, so re-run it before any further write (see step 1).

6. **Optionally pin it** explicitly:

   ```powershell
   adb shell settings put global webview_provider com.google.android.webview
   ```

## 8. Expected result on the reference device

Before and after, from `dumpsys webviewupdate`:

```text
Before:  Current WebView package: com.android.webview (version 66.0.3359.158)
After:   Current WebView package: com.google.android.webview (version 138.0.7204.179)
         Preferred: com.google.android.webview
         Alternatives: com.android.webview (66.0.3359.158)
```

The version moves **66.0.3359.158 → 138.0.7204.179**, "Preferred" follows the
new provider, and the old provider is retained as a fallback.

## 9. Applying the app to such a device

- The app itself needs the lowered `minSdk` from this branch
  (`feature/android-9-support`): the same APK built with `minSdk = 30` is
  rejected on the reference device with `INSTALL_FAILED_OLDER_SDK`.
- Installing a **release** build over a **debug** build fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (the two are signed with different keys).
  Uninstall first:

  ```powershell
  adb uninstall com.terebibro.tv
  ```

- The debug-only `adb forward` affordance described in the README
  ([Testing the controller on an emulator](../README.md#testing-the-controller-on-an-emulator))
  is **absent from release builds**. Release builds bind the TV's LAN address
  only and reject loopback peers, so plan to reach the controller over the LAN.

## 10. Honest limitations

The maintainer completed a full end-to-end pass on the real reference device
using the shipped `v0.2.0` release APK. That pass covered:

- The WebView provider swap itself (the framework patch in §6–§8).
- The app launching, the embedded control server binding, mDNS advertising, and
  immersive mode being applied (the legacy `systemUiVisibility` flag path).
- The **Back** ladder (closing the overlays, walking history, loading home, and
  opening the setup page at the root) and the long-press Back exit.
- **D-pad focus** traversal inside a real site, including via the controller's
  on-screen D-pad.
- **Controller pairing** against a second device using the 6-digit PIN, with
  live WebSocket status updates.
- **Real page rendering** of a real Home URL (Chromium 138).
- Reachability over the `<device-name>.local` mDNS address.
- The browser management actions (Restart WebView, Clear Cache, Clear Site
  Data), the Exit App button, and the release-only network policy (loopback
  refused, LAN served).

What is still not covered:

- This was **one API 28 unit**; a second unit may differ.
- **API 29 is untested** on hardware (as noted in §1).
- **API 30+** has only been exercised on an **emulator (Android 14 / API 34)**,
  not on physical hardware.
- There is **no CI and no automated device test suite**; this is a
  maintainer-run pass on one unit, not exhaustive coverage.

Also note: the reference device's **router NATs between subnets**, so the app's
local-subnet peer check is transparent on that topology — it cannot be exercised
as it would be on a normal flat LAN.

## See also

- [Supported Android versions](../README.md#supported-android-versions)
- `docs/v1-scope.md` — the `minSdk` deviation and its cost.
- `docs/implementation-plan.md` — the original specification target.
