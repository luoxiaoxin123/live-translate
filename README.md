# 实时翻译（Live Translate）

基于 **Google Gemini Live Translate**（`gemini-3.5-live-translate-preview`）的 Android 实时字幕应用。

- **字幕**：捕获手机内其它 App 播放的系统音频 → 实时翻译 → 半透明悬浮字幕  
- **设置**：自定义 API 端点 / Key / 模型、字号与背景透明度、译音开关与音量  
- **UI**： [MIUIX](https://github.com/compose-miuix-ui/miuix)（Compose）

> 首版范围：字幕 + 设置。对话同传为后续版本。

## 功能

| 模块 | 能力 |
|------|------|
| 字幕页 | 源/目标语言选择（记忆）、启动/停止、状态与预览 |
| 悬浮窗 | 细横把手拖动位置、右下角把手缩放框体（不改字号）、双语/仅译文 |
| 内录 | `MediaProjection` + `AudioPlaybackCapture` → 16 kHz PCM |
| Live API | WebSocket + `translationConfig`，对齐[官方文档](https://ai.google.dev/gemini-api/docs/live-api/live-translate) |
| 译音 | 云端返回音频；默认关；与原声并行播放，可调音量 |
| 连接测试 | 设置页验证端点 / Key / 模型 |

## 环境要求

- Android **10+**（API 29，内录 API）
- JDK 17+
- Android SDK 37（compileSdk）
- **能访问 Google 的网络**（`generativelanguage.googleapis.com`）。国内手机通常需要系统级代理/VPN，否则连接测试会失败
- AI Studio API Key

## 构建

```bash
# Windows Git Bash / PowerShell
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
./gradlew :app:assembleDebug
```

用 Android Studio 打开本仓库根目录，配置 SDK 后 Sync & Run。

## 配置

1. 安装 App → **设置**  
2. 填入 [Google AI Studio](https://aistudio.google.com/) API Key  
3. 可选：修改端点 / 模型 ID（默认已是官方 Live Translate）  
4. 点 **连接测试**  
5. 回到 **字幕** 页选择语言 → **启动字幕** → 授予悬浮窗与录屏权限  

## 项目结构

```
app/src/main/java/com/livetranslate/app/
  ui/           # 字幕页、设置页、主题
  service/      # 前台会话服务
  overlay/      # 悬浮字幕
  audio/        # 内录 + 译音播放
  live/         # Live Translate WebSocket 客户端
  data/         # DataStore + 加密 API Key
```

本地若有 `miuix/` 目录，仅作 UI 参考，**默认不参与构建**（使用 Maven 依赖）。已在 `.gitignore` 中忽略，避免把第三方 monorepo 一并开源。

## 隐私与安全

- API Key 使用 `EncryptedSharedPreferences` 存于本机  
- 音频流直连你配置的端点，本项目无自有后端  
- 开源发布前请勿提交 `local.properties` 或任何密钥  

## 许可

计划开源；请在首次发布时补充 `LICENSE`（建议 Apache-2.0 或 MIT，并注意 MIUIX 为 Apache-2.0）。

## 已知限制

- 部分 DRM / 禁止捕获的 App 可能无声  
- Live Translate 公开配置以**目标语**为主；源语「自动检测」最稳妥  
- 预览模型行为与配额可能变化；端点与模型 ID 可在设置中修改  
