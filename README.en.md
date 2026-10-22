# SmileHub

English | [中文](./README.md)

> Author: weixiao (微笑)

Connect a WorkBuddy account to any OpenAI-compatible client. The app runs a
local forwarding endpoint on your phone; point your editor, agent, or script at
it and it works — no official API, and no computer required.

```
Phone
├─ SmileHub
│    ├─ OAuth sign-in (China / Global)
│    ├─ Local forwarding endpoint  127.0.0.1:8765
│    └─ Overlay status panel (keeps the process from being frozen)
│
└─ Any OpenAI-compatible client
     └─ baseUrl = http://127.0.0.1:8765/v1
```

## Features

- **Account access** through the official CLI's OAuth flow — sign in once and the
  credential refreshes itself when it expires
- **Two builds side by side**: the China and Global builds are separate account
  systems, so both can be signed in at once and switched between instantly,
  with no re-login
- **Local forwarding endpoint**, OpenAI-compatible (`/v1/models`,
  `/v1/chat/completions`) with streaming passed through unchanged
- **Overlay keep-alive**: a small status panel keeps the app visible so the
  service is not frozen in the background. It can be dragged, collapsed, and
  its opacity and position lock are adjustable
- **Call history**: every call's model, token counts, and credit cost, with
  totals
- **Check-in and balance**: view remaining credit and claim the daily check-in
- **Built-in agent**: read/write files, run shell and Python, and drive the
  reverse-engineering and device tools from the chat page; sessions run
  concurrently, with context compaction and stream-resume for long tasks
- **Multimodal**: attach an image from the gallery or hand over a path — the
  model actually sees it

The list above is only an overview. Multimodal input, concurrent sessions, the
built-in agent toolchain, reverse engineering, ADB, and the Python runtime are
documented in full in [docs/FEATURES.md](./docs/FEATURES.md).

## Build

Requires JDK 21 and an Android SDK (with platform 35 and build-tools). Gradle
comes from the project's wrapper, so it does not need to be installed.

### Linux / macOS

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
```

### Windows

In PowerShell:

```powershell
"sdk.dir=$env:LOCALAPPDATA\Android\Sdk" | Out-File -Encoding ascii local.properties
.\gradlew.bat assembleDebug
```

Adjust the SDK path in `local.properties` to match your installation.

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

### Install on a device

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. **Sign in**: open the app → "Credential" → pick a build → tap sign in and
   complete the login in your browser
2. **Start the service**: "API" → turn the switch on
3. **Grant the overlay**: tap "Enable overlay" and allow it. This is what keeps
   the service alive in the background
4. **Point your client at it**: the "API" page shows the baseUrl and apiKey

### Client configuration

A `models.json`-style example:

```json
{
  "providers": {
    "workbuddy": {
      "baseUrl": "http://127.0.0.1:8765/v1",
      "api": "openai-completions",
      "apiKey": "wb-local",
      "models": [
        { "id": "hy3" }
      ]
    }
  }
}
```

Model names are listed on the app's "API" page; tapping one copies it.

## Why the overlay is needed

Android freezes a backgrounded app's process, and a frozen process stops serving
requests — which shows up as client timeouts. A foreground service is not enough
to prevent this: in this project's testing the service was still frozen and
still timed out.

An overlay keeps the app in the "visible" state, which raises its process
importance enough that it is no longer frozen. This needs no root and no extra
tooling — one permission grant in system settings.

## How it works

```
client request
  │  OpenAI protocol
  ▼
local forwarding endpoint (127.0.0.1:8765)
  │  ① reshape the body (the upstream requires stream, rejects the developer role)
  │  ② add the WorkBuddy-specific identity headers
  │  ③ attach your access token
  ▼
WorkBuddy upstream (copilot.tencent.com / www.workbuddy.ai)
  │  SSE stream
  ▼
passed through to the client unchanged
```

The endpoint listens on `127.0.0.1` only and requires a shared secret on every
request, so other apps on the device cannot call it.

The upstream endpoints are the private ones the WorkBuddy client uses, not a
public API. An upstream change can break the integration and would need
matching work here.

## Tech stack and dependencies

Build: AGP 8.9.1 · Gradle 8.11.1 · Kotlin 2.0.21 · JDK 21 · compileSdk 35 ·
minSdk 26 · targetSdk 28 · arm64-v8a only.

### UI and foundation

| Library | Version | Purpose |
| --- | --- | --- |
| Kotlin | 2.0.21 | Language and coroutines |
| Jetpack Compose BOM | 2025.05.00 | ui / foundation / animation / ui-tooling-preview |
| Material3 | 1.4.0-alpha18 | Material You components |
| material-icons-extended | via Compose BOM | Icons |
| activity-compose | 1.10.1 | Activity + Compose integration |
| AndroidX Core / Lifecycle | transitive | Notifications, FileProvider, lifecycle |

### Networking, protocol and security

| Library | Version | Purpose |
| --- | --- | --- |
| Conscrypt Android | 2.5.3 | TLS 1.3 `exportKeyingMaterial` for ADB pairing |
| BouncyCastle bcprov / bcpkix | 1.78.1 | Local certificates, ADB keys, signing keys |
| libadb-android | 3.1.1 (JitPack) | On-device ADB client and wireless pairing |
| HttpURLConnection (system) | — | Upstream requests and local forwarding |

### Reverse-engineering engine (`:engine`)

| Library | Version | Purpose |
| --- | --- | --- |
| apktool-lib | 2.10.0-wbhub.jar | APK decode / rebuild |
| brut.j.common / brut.j.util / brut.j.dir | 2.10.0 | apktool runtime |
| xpp3 | 1.1.6 | XML parsing |
| Guava | 33.3.0-android | apktool dependency |
| commons-lang3 / commons-io / commons-text | 3.17.0 / 2.16.1 / 1.12.0 | Utilities |
| apksig | 8.9.1 | APK signing and verification |
| smali / smali-baksmali | 3.0.8 | DEX disassembly / assembly |
| jadx-core / jadx-dex-input / jadx-input-api / jadx-zip | 1.5.6 | DEX to Java decompilation |
| slf4j-nop | 2.0.18 | No-op logger for jadx |
| gson | 2.14.0 | SO / ELF structure serialisation |
| Capstone | prebuilt `libwbcapstone.so` | SO disassembly |
| aapt2 / zipalign | Android build-tools | Resource compilation, APK alignment (`lib*_exec.so`) |

### Python runtime

| Library | Version | Purpose |
| --- | --- | --- |
| Chaquopy | 17.1.0 | Embed CPython in the APK |
| CPython | 3.12 | The agent's `python` tool, standard library only |

All libraries remain under their respective licenses; follow each project's
terms when redistributing.

## References

- [ardeyouxipianyi/workbuddy2api-hub](https://github.com/ardeyouxipianyi/workbuddy2api-hub)
- [xc3qaq/WorkBuddy-Hub-Android](https://github.com/xc3qaq/WorkBuddy-Hub-Android)

Thanks to both projects for reference on protocol adaptation and design.

## Author

- weixiao (微笑)
- QQ: 791940981

## Known limitations

- Overlay keep-alive depends on how the system judges importance; aggressive
  background policies may still restrict it
- A Global account may need its trial activated in the official client first,
  otherwise the upstream refuses billing-related requests
- Depends on private WorkBuddy endpoints, which may need adapting after an
  upstream update
- Verified on Android only

## Disclaimer

- This project is for personal study and research only, and drives only the
  user's own account from their own device
- Users must comply with WorkBuddy's terms of service; any consequence of using
  this project is the user's own responsibility
- This project is not affiliated with, authorized by, or endorsed by Tencent or
  WorkBuddy; names are used only to describe compatibility

## Maintenance

Build, release, layout, troubleshooting and known debt:
[docs/MAINTENANCE.md](./docs/MAINTENANCE.md).

## License

[MIT](./LICENSE)
