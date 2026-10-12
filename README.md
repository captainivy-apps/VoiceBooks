# Android Voicebook

原生 Kotlin + Jetpack Compose 有声书 App。支持本地导入 EPUB/TXT/MOBI/AZW/AZW3，离线 TTS 逐句朗读。

## 功能

- **书库**：上传、排序、左滑删除、批量删除、播放进度展示
- **播放**：封面/字幕切换、播放控制、快进快退、速度调节、睡眠定时
- **设置**：屏幕常亮、Sherpa-ONNX TTS 模型下载与管理

## 构建

```bash
./gradlew assembleDebug
```

需要 JDK 17+ 与 Android SDK 34。

## TTS 说明

- 播放前须在「设置 → TTS 引擎管理」中**下载并设为默认** Sherpa-ONNX 离线模型
- 未下载模型时无法播放，会提示前往设置下载
- 合成或播放失败时会在界面显示具体错误信息，详情可查看 logcat（`SherpaOnnxEngine` / `TtsPlaybackService`）
- 中文推荐模型：`vits-piper-zh_CN-xiao_ya-medium`（高品质）或 `vits-piper-zh_CN-xiao_ya-medium-int8` / `vits-icefall-zh-aishell3`（轻量）
- 中英双语推荐模型：`matcha-icefall-zh-en`（Matcha，需额外下载 Vocos 声码器，应用会自动一并获取）
- 设置页 TTS 管理已按语言分区展示，可用顶部 Chip 快速筛选
- 英文推荐模型：`vits-piper-en_US-lessac-medium`

若 Maven 依赖 `com.k2fsa.sherpa.onnx:sherpa-onnx` 无法解析，可从 [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) 下载 AAR 放入 `app/libs/` 并改用：

```kotlin
implementation(files("libs/sherpa-onnx.aar"))
```

## 技术栈

- Kotlin + Compose + Material3
- Room + DataStore + WorkManager
- epublib + Jsoup（EPUB）
- 纯 Kotlin MOBI 解析（非 DRM）
- Sherpa-ONNX（离线 TTS，需下载模型）
