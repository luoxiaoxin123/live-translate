# Live Translate

[中文文档](README.zh-CN.md)

Android app for **real-time subtitles** powered by [Google Gemini Live Translate](https://ai.google.dev/gemini-api/docs/live-api/live-translate) (model `gemini-3.5-live-translate-preview`).

The app captures **audio playing in other apps** (videos, meetings, etc.), sends it to the Live Translate API, and shows a **draggable semi-transparent floating overlay**. Optional **translated speech** can play in parallel with the original audio.

---

## Features

| Area | Description |
|------|-------------|
| **Subtitles tab** | Source / target language (remembered), start / stop, status and preview |
| **Floating overlay** | Over other apps; thin top grabber to move; corner handle to resize (font size unchanged) |
| **Display modes** | Translation only, or bilingual (source + translation with a divider) |
| **Auto-scroll** | Separate panes for source / translation; **scrolls one line only when a line wraps** |
| **System audio capture** | `MediaProjection` + `AudioPlaybackCapture` → 16 kHz PCM |
| **Live API** | WebSocket + `translationConfig`, aligned with the official Live Translate docs |
| **Translated voice** | Off by default; plays in parallel without pausing the video; volume up to **200%** (digital gain) |
| **Settings** | Custom endpoint / API key / model, connection test, font size and background opacity, permissions, about |
| **Language** | Follows the system: Chinese device language → Chinese UI; otherwise → English |

---

## Requirements

### Using the app

- Android **10+** (API 29, required for system audio capture)
- A [Google AI Studio](https://aistudio.google.com/) API key

### Building from source

- JDK **17+** (21 recommended)
- Android SDK as required by the project (compileSdk **37**, etc.)
- Android Studio or command-line Gradle

---

## Install & use

### 1. Install

- Install a release build from [GitHub Releases](../../releases) when available
- Or build a debug APK locally (see [Build](#build))

### 2. Configure API

1. Open the app → **Settings**
2. Enter your **API Key**
3. Defaults usually need no change:
   - **Endpoint:**  
     `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`
   - **Model:** `gemini-3.5-live-translate-preview`
4. Tap **Save and test connection**

### 3. Start subtitles

1. Open the **Subtitles** tab  
2. Choose **source** and **target** languages  
3. Tap **Start subtitles**  
4. Grant when prompted:
   - **Display over other apps** (overlay)
   - **Screen capture / cast** (system audio; system dialog)
5. Play a foreign-language video or other media; the translation appears in the floating window

### 4. Overlay tips

- Drag the **thin top bar** to move  
- Drag the **corner handle** to resize (font size unchanged)  
- In Settings: font size, background opacity, bilingual toggle, reset appearance  
- Optionally enable **Play translated voice**; volume can go past 100% to sit above the original track  

---

## Build

```bash
# Windows Git Bash example: set JDK
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"

# If local.properties is missing, set the SDK path, e.g.:
# sdk.dir=C:/Users/YOUR_NAME/AppData/Local/Android/Sdk

./gradlew :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Or open the **repository root** in Android Studio → Sync → Run.

> If the project path contains non-ASCII characters, the project sets `android.overridePathCheck=true`.

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

A local `miuix/` directory, if present, is for reference only and is **not** part of the default build (listed in `.gitignore`). The app depends on MIUIX from **Maven Central**.

---

## Privacy

- The API key stays on device (`EncryptedSharedPreferences` when available)  
- Audio is sent only to the **endpoint you configure** (default: Google AI Studio Live API)  
- This project has **no** backend that collects keys or audio  
- Do not commit `local.properties`, secrets, or signing keys  

---

## Known limitations

- Some apps / DRM content **block** playback capture → nothing to translate  
- Live Translate is driven mainly by **target language**; source **Auto-detect** is the most reliable choice  
- Preview models and quotas may change; endpoint and model ID are configurable in Settings  

---

## Third-party

See [NOTICE](NOTICE). Primary UI dependency: [MIUIX](https://github.com/compose-miuix-ui/miuix) (Apache-2.0).

Live Translate documentation:  
https://ai.google.dev/gemini-api/docs/live-api/live-translate

---

## License

[Apache License 2.0](LICENSE)

---

## Acknowledgements

- [Linux.do](https://linux.do)
