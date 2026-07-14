# 版本号与 GitHub Actions 发布建议

## App 里 GitHub 链接怎么办？

仓库还没建时：

1. 在 `app/src/main/res/values/strings.xml` 里保留空的：
   ```xml
   <string name="github_url" translatable="false"></string>
   ```
2. 设置页「关于」会显示「尚未配置…」，**不假装有链接**。
3. 你创建公开仓库后，把真实地址填进去，例如：
   ```xml
   <string name="github_url" translatable="false">https://github.com/你的用户名/live-translate</string>
   ```
4. 提交一次即可，App 内会显示并可点击打开。

不建议瞎编一个还没建的 URL。

---

## Workflow 怎么知道下一个版本号？

**不推荐**让 bot 每次合并都来问你「版本号填多少」。自动化发布更稳的是下面几种之一。

### 方案 A（推荐）：Git Tag 驱动发布

- 平时 `main` 合并 **只编译**（可选：上传 debug artifact，不发正式版）。
- 你准备发版时本地或 GitHub 上打 tag：
  ```bash
  git tag v0.1.0
  git push origin v0.1.0
  ```
- Workflow `on: push: tags: ['v*']` 被触发：
  1. 从 tag 解析版本：`v0.1.0` → `versionName=0.1.0`
  2. `versionCode` 用 tag 时间戳、或维护一个递增整数、或用 GitHub run number
  3. 编译 release/debug APK
  4. 创建 GitHub Release，挂上 APK

**优点**：版本号由你显式决定；不会每次 merge 都自动涨版。  
**首版**：直接打 `v0.1.0`（或 `v0.1`，建议统一成三段 `0.1.0`）。

### 方案 B：workflow_dispatch 手动填版本

- `on: workflow_dispatch` + inputs：
  - `version_name`（例如 `0.2.0`）
  - 可选 `version_code`
- 你在 Actions 页面点「Run workflow」并填写版本。

**优点**：灵活。  
**缺点**：容易忘；和 git 历史里的 tag 可能不一致。可与 A 并用：手动跑时也要求同步打 tag。

### 方案 C：合并 main 就自动发版（一般不建议个人 MVP）

- 每次 push main 用 `github.run_number` 当 versionCode，`0.1.${{ github.run_number }}` 当 versionName。
- 适合「每日构建」，不适合语义化版本。

---

## 建议你落地的流程

```
开发 → PR/直接 push main
         ↓
    CI：编译通过（不强制发版）
         ↓
    你确认要发 0.1.0 → git tag v0.1.0 && push tag
         ↓
    Release workflow：改 gradle 版本或 -PversionName → 编 APK → GitHub Release
```

版本号**写在哪里**（二选一即可）：

1. **Tag 为准**：workflow 用 `-PVERSION_NAME=0.1.0 -PVERSION_CODE=1` 覆盖 `app/build.gradle.kts`  
2. **gradle 为准**：发版前手动改 `versionName` / `versionCode`，再打同名 tag，workflow 只读 gradle

个人项目更推荐 **1（tag 为准）**，少改文件。

---

## versionCode 怎么定？

| 规则 | 说明 |
|------|------|
| 手动 +1 | 最清晰：0.1.0→1，0.1.1→2 |
| GitHub `run_number` | 简单单调递增，不必记 |
| 从 tag 解析 | 仅适合严格 `MAJOR.MINOR.PATCH` 映射表 |

Play 商店要求 versionCode **严格递增**；只发 GitHub Release 时也建议递增。

---

## 和「bot 问我版本号」的对比

| 做法 | 评价 |
|------|------|
| 每次 merge 发消息问版本 | 噪声大、难自动化、容易漏回 |
| Tag / 手动 workflow 填版本 | **推荐**：你发版时才决定数字 |
| 自动 0.1.run_number | 适合 CI 产物，不适合对外语义版本 |

---

## 首版 0.1 怎么写？

建议统一为：

- `versionName`: **`0.1.0`**
- `versionCode`: **`1`**
- Git tag: **`v0.1.0`**

「0.1」可以当对外口头版本，仓库与 Gradle 用三段式更省事。
