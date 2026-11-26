# EZ SEB (Safe Exam Browser) Bypass

Browser extension to bypass the execution of quizzes in Safe Exam Browser (SEB).

The extension intercepts outgoing requests of the exam tab and rewrites them:

- `User-Agent` → SEB user agent string
- `X-SafeExamBrowser-ConfigKeyHash` → SHA-256 of `request URL + config key hash`, derived from your `.seb` file

## Compatibility

Manifest V3, Chromium-based browsers (Chrome, Edge, Brave, Opera) on Windows, macOS and Linux.

Chrome no longer allows `webRequestBlocking` for sideloaded extensions, so interception is done through the `chrome.debugger` API. Chrome shows a "SEB started debugging this browser" banner while the extension is enabled — that is expected and required.

## Installation

### From a release

1. Download the latest release and unzip it. You should see a `dist` folder.
2. Open `chrome://extensions/`.
3. Enable "Developer mode".
4. Click "Load unpacked" and select the `dist` folder (the one containing `manifest.json`).

### From source

```sh
git clone https://github.com/ArthaFreestyle/SEB-BYPASSER
cd SEB-BYPASSER
npm install
npm run build
```

Then load the generated `dist` folder the same way.

Checks:

```sh
npm run check   # SEB parse / serialize / hash self-check
```

## Usage

1. Click the extension icon. A configuration window opens.
2. Click "Choose File" and pick your SEB configuration file (`.seb` / `.xml`).
3. The extension reports `ENABLED` and lists the start URL.
4. Reload the exam tab (or navigate to the start URL) so the first document request is signed.

While enabled:

- "Request URLs filter" accepts a JSON array of Chrome match patterns, e.g. `["https://exam.example.com/*"]`. Narrowing it avoids touching unrelated traffic.
- "Update and set to the domain of the start URL" derives the filter from the SEB config.
- "Disable" detaches the debugger and stops interception.

`debugging.html` (linked at the bottom of the popup) shows the parsed config JSON, the config hash, and the config key for an arbitrary URL.

## How it works

- `src/seb-tools.ts` parses the SEB plist XML into a dictionary, serializes it the way SEB does (sorted keys, `originatorVersion` dropped, compact JSON) and hashes it with SHA-256. The config key for a request is `SHA256(requestURL + configHash)`.
- `src/header-handler.ts` attaches the debugger to each tab, enables CDP `Fetch` interception, and rewrites headers on `Fetch.requestPaused`.
- `src/background.ts` is the MV3 service worker: message API, state persisted in `chrome.storage.session`, reattach after service worker restarts.
