# Live Translate

[中文文档](README.zh-CN.md)

Android app for **real-time subtitles** powered by [Google Gemini Live Translate](https://ai.google.dev/gemini-api/docs/live-api/live-translate) (model `gemini-3.5-live-translate-preview`).

The app can capture **other apps' playback**, the **microphone**, or both, send audio to the Live Translate API, and show a **draggable semi-transparent floating overlay**. Optional **translated speech** can play in parallel with the original audio. After you stop a session, you can export the source and translation as Markdown.

---

## Features

| Area | Description |
|------|-------------|
| **Subtitles tab** | Source / target language (remembered), audio source, start / stop, status and preview |
| **Audio source** | Media / microphone / media+mic (remembered; mic-only skips screen-capture permission) |
| **Floating overlay** | Over other apps; thin top grabber to move; corner handle to resize (font size unchanged) |
| **Display modes** | Translation only, or bilingual (source + translation with a divider) |
| **Auto-scroll** | Separate panes for source / translation; **scrolls one line only when a line wraps** |
| **Audio capture** | Media: `MediaProjection` + `AudioPlaybackCapture`; mic: `AudioRecord` → 16 kHz PCM |
| **Live API** | WebSocket + `translationConfig`, aligned with the official Live Translate docs |
| **Translated voice** | Off by default; plays in parallel without pausing the video; volume up to **200%** (digital gain) |
| **Multiple API keys** | Up to 10 keys; rotate on each session; connection test checks every key |
| **Export** | After stop, export this session as Markdown to Downloads |
| **Settings** | Endpoint / keys / model, connection test, font size and background opacity, permissions, about |
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
2. Enter one or more **API keys** (up to 10; `+` on the last row adds a field, `−` on non-first rows removes one)
3. Defaults usually need no change:
   - **Endpoint:**  
     `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`
   - **Model:** `gemini-3.5-live-translate-preview`
4. Tap **Save and test connection** (tests every filled-in key)

### 3. Start subtitles

1. Open the **Subtitles** tab  
2. Choose **source** and **target** languages  
3. Choose **audio source** (media / microphone / both)  
4. Tap **Start subtitles**  
5. Grant when prompted:
   - **Display over other apps** (overlay)
   - **Screen capture / cast** if media is selected  
   - **Microphone** if mic is selected  
6. Play foreign-language media or speak into the mic; the translation appears in the floating window  

After you stop, if there is content, use **Export this session as Markdown** on the subtitles tab (saved under Downloads, e.g. `7月15日-14:30-翻译结果.md`).

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
  audio/        # Media capture / mic / mix + translated audio playback
  live/         # Live Translate WebSocket client
  data/         # DataStore settings + multi API key storage
  util/         # Session Markdown export, etc.
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

- Some apps / DRM content **block** playback capture → nothing to translate in media mode (try microphone)  
- Live Translate is driven mainly by **target language**; source **Auto-detect** is the most reliable choice  
- Continuous streams do not align sentence-by-sentence; Markdown export is full source + full translation, not line pairs  
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
