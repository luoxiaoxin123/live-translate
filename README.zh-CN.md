# 实时翻译（Live Translate）

[English](README.md)

基于 [Google Gemini Live Translate](https://ai.google.dev/gemini-api/docs/live-api/live-translate)（模型 `gemini-3.5-live-translate-preview`）的 **Android 实时字幕**应用。

App 可捕获**其它应用正在播放的声音**、**麦克风**或两者混合，推到 Live Translate 接口，并在屏幕上显示**可拖动的半透明悬浮字幕**。可选打开**译音**，与原片声音并行播放。停止后还可把本次原文/译文导出为 Markdown。

---

## 功能

| 模块 | 说明 |
|------|------|
| **字幕页** | 源/目标语言（会记住）、声音来源、启动/停止、状态与预览 |
| **声音来源** | 媒体音 / 麦克风 / 媒体+麦克风（会记住；纯麦克风不弹录屏） |
| **悬浮字幕** | 盖在其它 App 上；顶部细把手拖动位置；右下角把手缩放框体（不改字号） |
| **显示模式** | 仅译文，或双语（原文 + 译文，中间横线分隔） |
| **自动滚动** | 原文区 / 译文区各自滚动；**只有换行时才滚一行**，避免字跟着抖 |
| **音频采集** | 媒体：`MediaProjection` + `AudioPlaybackCapture`；麦克风：`AudioRecord` → 16 kHz PCM |
| **Live API** | WebSocket + `translationConfig`，对齐官方 Live Translate 文档 |
| **译音** | 默认关闭；与原声并行、不暂停视频；音量最高可到 **200%**（数字增益） |
| **多 API Key** | 最多 10 个；启动会话时按顺序轮询；连接测试会测完每一个 |
| **导出** | 停止后导出本次翻译为 Markdown 到系统下载目录 |
| **设置** | 端点 / Key / 模型、连接测试、字号与背景透明度、权限、关于 |
| **语言** | 跟随系统：手机是中文 → App 中文；非中文 → App 英文 |

---

## 运行要求

### 使用 App

- Android **10 及以上**（API 29，系统内录需要）
- [Google AI Studio](https://aistudio.google.com/) 的 API Key

### 从源码编译

- JDK **17+**（推荐 21）
- 工程要求的 Android SDK（compileSdk **37** 等）
- Android Studio 或命令行 Gradle

---

## 安装与使用

### 1. 安装

- 有正式包时从 [GitHub Releases](../../releases) 安装
- 本地编译 debug 包后安装（见下方 [构建](#构建)）

### 2. 配置 API

1. 打开 App → **设置**
2. 填入一个或多个 **API Key**（最多 10 个；最后一行左侧 `+` 添加，非首行右侧 `−` 删除）
3. 默认一般不用改：
   - **端点：**  
     `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`
   - **模型：** `gemini-3.5-live-translate-preview`
4. 点 **保存并测试连接**（会逐个测试已填写的 Key）

### 3. 启动字幕

1. 打开 **字幕** 页  
2. 选择 **源语言**、**目标语言**  
3. 选择 **声音来源**（媒体音 / 麦克风 / 两者）  
4. 点 **启动字幕**  
5. 按提示授权：
   - **显示在其他应用上层**（悬浮窗）
   - 选了媒体音时：**录屏 / 投屏**（系统内录音频）
   - 选了麦克风时：**麦克风** 权限  
6. 播放外文媒体或对着麦说话，悬浮窗中会出现译文  

停止后若有内容，可在字幕页 **导出本次翻译为 Markdown**（保存到下载目录，文件名类似 `7月15日-14:30-翻译结果.md`）。

### 4. 悬浮窗小技巧

- 拖顶部 **细横条** 移动位置  
- 拖右下角 **把手** 改宽高（字号不变）  
- 设置里可调：字号、黑底透明度、双语开关、重置外观  
- 可选打开 **播放译音**，音量可拉过 100% 以便压过原片外文声  

---

## 构建

```bash
# Windows Git Bash 示例：指定 JDK
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"

# 若没有 local.properties，需配置 SDK 路径，例如：
# sdk.dir=C:/Users/你的用户名/AppData/Local/Android/Sdk

./gradlew :app:assembleDebug
```

产物路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

也可在 Android Studio 中打开**仓库根目录** → Sync → Run。

> 若工程路径含中文等非 ASCII 字符，项目已设置 `android.overridePathCheck=true`。

---

## 项目结构

```text
app/src/main/java/com/livetranslate/app/
  ui/           # 字幕页、设置页、主题（MIUIX）
  service/      # 前台会话服务
  overlay/      # 悬浮字幕窗
  audio/        # 媒体内录 / 麦克风 / 混音 + 译音播放
  live/         # Live Translate WebSocket 客户端
  data/         # DataStore 设置 + 多 API Key 存储
  util/         # 会话 Markdown 导出等
```

本地若存在 `miuix/` 目录，仅作参考，**默认不参与构建**（已加入 `.gitignore`）。App 通过 **Maven Central** 依赖 MIUIX。

---

## 隐私

- API Key 仅保存在本机（可用时走 `EncryptedSharedPreferences`）  
- 音频只发往**你配置的端点**（默认 Google AI Studio Live API）  
- 本项目**没有**自建后端收集密钥或音频  
- 请勿提交 `local.properties`、密钥或签名文件  

---

## 已知限制

- 部分 App / DRM 内容**禁止**被内录 → 媒体音模式下无声可译（可改用麦克风）  
- Live Translate 配置以**目标语**为主；源语言选「自动检测」最稳妥  
- 连续流字幕不好做句对句对齐，导出 MD 是「全文原文 + 全文译文」两段，不是逐句对照  
- 预览模型与配额可能变化；端点与模型 ID 可在设置中修改  

---

## 第三方

见 [NOTICE](NOTICE)。主要 UI 依赖：[MIUIX](https://github.com/compose-miuix-ui/miuix)（Apache-2.0）。

Live Translate 官方文档：  
https://ai.google.dev/gemini-api/docs/live-api/live-translate

---

## 许可

[Apache License 2.0](LICENSE)

---

## 致谢

- [Linux.do](https://linux.do)
