# Live Translate

[中文文档](README.zh-CN.md)

Android app for **real-time subtitles** powered by [Google Gemini Live Translate](https://ai.google.dev/gemini-api/docs/live-api/live-translate) (`gemini-3.5-live-translate-preview`).

It captures **system playback audio** from other apps (videos, meetings, etc.), streams it to the Live Translate API, and shows a **draggable floating overlay** with the translation. Optional **translated speech** can play alongside the original audio.

> **v0.1 scope:** Subtitles + Settings. Face-to-face conversation mode is planned for later.

**UI:** [MIUIX](https://github.com/compose-miuix-ui/miuix) (Compose) · gray page + white rounded cards (MIUI-style)

**License:** [Apache License 2.0](LICENSE)

---

## Features

| Area | What it does |
|------|----------------|
| **Subtitles tab** | Pick source / target language (remembered), start / stop, status + preview |
| **Floating overlay** | Semi-transparent captions over other apps; thin grabber to move; corner handle to resize (font size unchanged) |
| **Display modes** | Translation only, or bilingual (source + translation, split with a divider) |
| **Auto-scroll** | Per pane; scrolls only when a **new line wraps**, not on every character |
| **System audio capture** | `MediaProjection` + `AudioPlaybackCapture` → 16‑bit PCM @ 16 kHz |
| **Live API** | WebSocket + `translationConfig` (official Live Translate setup) |
| **Translated voice** | Off by default; plays in parallel (does not pause the video); volume up to **200%** via digital gain |
| **Settings** | Custom endpoint / API key / model ID, connection test, font size, background opacity, permissions, About |
| **Localization** | Follows system language: **Chinese** if the device is Chinese, otherwise **English** |

---

## Requirements

### Run the app (phone)

- Android **10+** (API 29; required for system audio capture)
- Network that can reach **Google** (`generativelanguage.googleapis.com`)
- A [Google AI Studio](https://aistudio.google.com/) API key

### Build from source (PC)

- JDK **17+** (21 recommended)
- Android SDK with **compileSdk 37** / build-tools as in the project
- Android Studio or command-line Gradle

---

## Install & use

### 1. Install

- Install a release APK from [GitHub Releases](../../releases) when available, **or**
- Build a debug APK locally (see [Build](#build)) and install it.

### 2. Configure API

1. Open the app → **Settings**
2. Paste your **API Key** (stored encrypted on device when possible)
3. Defaults (usually no change needed):
   - **Endpoint:**  
     `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`
   - **Model:** `gemini-3.5-live-translate-preview`
4. Tap **Save and test connection**

If the test stays on “Connecting”, the phone often **cannot reach Google**. Fix network / proxy / VPN first; the default endpoint is the official one.

### 3. Start subtitles

1. Open the **Subtitles** tab  
2. Choose **source** and **target** languages (saved for next launch)  
3. Tap **Start subtitles**  
4. Grant when prompted:
   - **Display over other apps** (overlay)
   - **Screen capture / record** (system audio; Android will show a system dialog)
5. Play a foreign-language video (or other media). Captions appear in the floating window.

### 4. Overlay tips

- Drag the **thin bar** at the top to move the box  
- Drag the **corner handle** to resize (does not change font size)  
- In Settings: font size, black background opacity, bilingual on/off, reset appearance  
- Optional: enable **Play translated voice** and raise volume above 100% if you want it louder than the original track  

---

## Build

```bash
# Set JDK if needed (example on Windows Git Bash)
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"

# SDK path (create local.properties if missing)
# sdk.dir=C:/Users/YOU/AppData/Local/Android/Sdk

./gradlew :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Or open the **repository root** in Android Studio → Sync → Run.

> If the project path contains non-ASCII characters, `gradle.properties` already sets `android.overridePathCheck=true`.

---

## Project structure

```text
app/src/main/java/com/livetranslate/app/
  ui/           # Subtitles tab, Settings, theme (MIUIX)
  service/      # Foreground session service
  overlay/      # Floating caption window
  audio/        # System capture + translated audio playback
  live/         # Live Translate WebSocket client
  data/         # DataStore settings + API key storage
```

A local `miuix/` tree (if present) is for reference only and is **gitignored**. The app depends on MIUIX from **Maven Central**.

---

## Privacy

- API key is stored on device (`EncryptedSharedPreferences` when available)  
- Audio is sent only to the endpoint **you** configure (default: Google AI Studio Live API)  
- This project does **not** ship a backend that collects your key or audio  
- Do not commit `local.properties`, keys, or keystores  

---

## Known limitations

- Some apps / DRM content **block** playback capture → no audio to translate  
- Live Translate is driven mainly by **target language**; source “Auto-detect” is the robust default  
- Preview models and quotas may change; endpoint and model ID are configurable  
- Conversation / dual-mic “interpreter” mode is **not** in v0.1  

---

## Versioning & releases

Suggested flow: day-to-day commits only build; cut a release by tagging `v0.1.0`, etc.  
See [docs/release-versioning.md](docs/release-versioning.md).

Current app version in Gradle: **0.1.0** (`versionCode` 1).

After you create a public GitHub repo, set the URL in:

```xml
<!-- app/src/main/res/values/strings.xml -->
<string name="github_url" translatable="false">https://github.com/YOUR_USER/YOUR_REPO</string>
```

Settings → About will then show a tappable link.

---

## Third-party

See [NOTICE](NOTICE). Notable dependency: [MIUIX](https://github.com/compose-miuix-ui/miuix) (Apache-2.0).

Live Translate API docs:  
https://ai.google.dev/gemini-api/docs/live-api/live-translate

---

## License

[Apache License 2.0](LICENSE)
