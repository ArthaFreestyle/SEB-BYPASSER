# SEB Bypass (Desktop Extension & Android APK)

Tools to bypass Safe Exam Browser (SEB) client checks on Chromium desktop browsers and Android devices using your `.seb` configuration file.

Both targets intercept outgoing HTTP requests and rewrite headers cleanly:

- `User-Agent` → Windows SEB 3.7.0 user agent string
- `sec-ch-ua-platform` → `"Windows"`
- `X-SafeExamBrowser-ConfigKeyHash` → SHA-256 of `request URL (without fragment) + config hash`, derived from your `.seb` file

## Compatibility

- **Desktop Extension:** Manifest V3 for Chromium-based browsers (Chrome, Edge, Brave, Opera) on Windows, macOS, and Linux. Uses the `chrome.debugger` CDP `Fetch` API (because Chrome disables `webRequestBlocking` for sideloaded MV3 extensions), so Chrome displays the `"SEB" started debugging this browser` banner while enabled.
- **Android APK:** Android 8.0+ (API 26+, `arm64-v8a` and `armeabi-v7a`) built with **Mozilla GeckoView 147** and a bundled MV2 WebExtension using `webRequestBlocking`.

> [!NOTE]
> Unencrypted XML plist `.seb` / `.xml` files are supported on both platforms. On Android, gzip-compressed (`plnd` / `.seb` gzip) XML configs are also automatically decompressed. Password-encrypted `.seb` (`pswd`/`pwcc`) and binary `bplist` files are not supported.

## Installation

### Option 1: Android APK

#### From Releases (Recommended)
1. Download an APK from the [Releases](https://github.com/Overlord08X/SEB-Bypass/releases) page (see [`v1.1.0`](https://github.com/Overlord08X/SEB-Bypass/releases/tag/v1.1.0)):
   - **`SEB-Bypass-arm64-debug.apk`** — for most modern 64-bit Android phones (`arm64-v8a`)
   - **`SEB-Bypass-armeabi-debug.apk`** — for older 32-bit Android devices (`armeabi-v7a`)
2. Open the APK on your phone and allow installation from unknown sources if prompted.

#### Build APK from source
Prerequisites: Node.js 22+, Java 21, and Android SDK (`platforms;android-36`, `build-tools;36.0.0`).

```sh
git clone https://github.com/Overlord08X/SEB-Bypass.git
cd SEB-Bypass
npm install
npm run build:android-ext
gradle -p android assembleDebug --no-daemon
```

APKs are also automatically built on push via [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml).

---

### Option 2: Desktop Chromium Extension

#### From Releases
1. Download `SEB-Bypass-v1.0.0.zip` (or the latest desktop release archive) from [Releases](https://github.com/Overlord08X/SEB-Bypass/releases) and unzip it so you have the `dist` folder.
2. Open `chrome://extensions/` and enable **Developer mode**.
3. Click **Load unpacked** and select the `dist` folder containing `manifest.json`.

#### Build from source
```sh
git clone https://github.com/Overlord08X/SEB-Bypass.git
cd SEB-Bypass
npm install
npm run build
```

Then load the generated `dist` folder in `chrome://extensions/`.

## Checks

Run all TypeScript self-checks (SEB parser/serializer/ConfigKeyHash, CDP header handler, and Android WebExtension + manifest checks):

```sh
npm run check
```

## Usage

### Android App
1. Open **SEB Bypass** and tap **Choose .seb or .xml File** (or **Load .seb** in the top bar).
2. Select your `.seb` or `.xml` config file. The status badge turns **`ENABLED`** and automatically navigates to the config's `startURL`.
3. Use the toolbar controls:
   - **Start Domain / All URLs** toggles whether SEB headers are injected only on the `startURL` origin or across all URLs (`<all_urls>`).
   - **Disable** stops header interception and restores the default User-Agent.
   - Browser controls (`<`, `>`, URL bar, `↻`, `Go`) navigate normally within GeckoView.
   - Standard web prompts (`alert()`, `confirm()`, `prompt()`, `<select>` dropdowns, POST resubmission confirmation, HTTP auth, and `<input type="file">`) are handled via native Android dialogs.

### Desktop Extension
1. Click the extension icon to open the configuration popup.
2. Click **Choose .seb or .xml file** (or drag and drop your `.seb` / `.xml` file into the upload area).
3. Once status shows `ENABLED`, reload the exam tab (or open the displayed start URL) so the document request is signed.
4. Optional controls while enabled:
   - **Request URLs filter**: accepts a JSON array of Chrome match patterns, e.g. `["https://exam.example.com/*"]`, to limit interception to exam domains.
   - **Use Start URL Domain**: narrows the filter automatically to the config start URL domain.
   - **Disable**: detaches the debugger and stops interception.
5. **SEB file debugging** (`debugging.html`, linked at the bottom of the popup) inspects the canonical serialized JSON, displays the config hash, and computes `X-SafeExamBrowser-ConfigKeyHash` for any URL.

## How It Works

### Shared SEB Core
- `src/seb-tools.ts` parses SEB plist XML into a dictionary, serializes it canonically (case-insensitive sorted keys, dropping `originatorVersion` and empty root dicts), and computes `configHash = SHA256(serializedJson)` and `X-SafeExamBrowser-ConfigKeyHash = SHA256(requestURLWithoutFragment + configHash)`.

### Desktop Chromium Architecture
- `src/header-handler.ts` attaches `chrome.debugger` to tabs, enables CDP `Fetch` interception, and rewrites headers on `Fetch.requestPaused` while ignoring expected detach races.
- `src/background.ts` runs the MV3 service worker, persists state in `chrome.storage.session`, and restores handlers across worker restarts.
- `src/popup.tsx` and `src/debugging.tsx` provide the dark-themed React configuration and hash debugging UI.

### Android GeckoView Architecture
- `src/android-ext/background.ts` (bundled by `vite.android.config.ts` into `android/app/src/main/assets/seb-extension/background.js`) listens for commands from Kotlin via `browser.runtime.connectNative("seb_bypass")` and modifies outgoing GET/POST request headers synchronously using MV2 `webRequest.onBeforeSendHeaders` (`['blocking', 'requestHeaders']`).
- `android/app/src/main/assets/seb-extension/manifest.json` declares GeckoView MV2 permissions (`geckoViewAddons`, `nativeMessaging`, `webRequest`, `webRequestBlocking`).
- `android/.../SebApp.kt` initializes `GeckoRuntime` strictly in the main app process (avoiding re-initialization in `:tab*` / `:gpu` child processes) and bridges messages to the built-in extension.
- `android/.../MainActivity.kt` manages `.seb` file decoding (plain XML, UTF-8/UTF-16 BOM, gzip `plnd`, `sebs://`/`seb://` start URLs), GeckoView navigation/session recovery, and IME insets.
- `android/.../SebPromptDelegate.kt` implements `GeckoSession.PromptDelegate` dialogs (`alert`, `confirm`, `prompt`, `<select>`, file picker, auth, POST resend) and `SebCrashReporter.kt` captures uncaught crashes to display on next launch.
