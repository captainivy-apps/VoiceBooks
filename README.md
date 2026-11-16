# Android VoiceBook

原生 Kotlin + Jetpack Compose 有声书 App。支持 EPUB 导入（本地 / URL / 局域网浏览器上传），通过 Sherpa-ONNX 离线 TTS 逐句朗读。

## 功能

- **书库**：本地选择、URL 导入（仅 EPUB 直链）、局域网浏览器上传（默认端口 `8765`）；导入阶段与进度展示、重复自动跳过、失败重试；按上传时间/最近播放排序；单个删除与批量删除；书籍信息查看与元数据刷新
- **播放**：封面/字幕切换、逐句朗读与高亮、快进/快退 10 句、速度调节、睡眠定时（15/30/60 分钟、自定义、播完本书）、后台与锁屏播放（前台服务 + MediaSession + WakeLock）
- **设置**：播放时屏幕常亮、后台播放电池优化引导、局域网上传开关、模型镜像地址、书库重建、Sherpa-ONNX TTS 模型下载与管理（按语言分区、多音色选择、试听）

## 构建

```bash
./gradlew assembleDebug
```

需要 JDK 17+ 与 Android SDK 34（minSdk 26）。

Sherpa-ONNX 以本地 AAR 引入，需将 `sherpa-onnx-1.13.3.aar` 放入 `app/libs/`（仓库不提交 AAR），可从 [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) 下载。

## TTS 说明

- 播放前须在「设置 → TTS 引擎管理」中**下载并设为默认** Sherpa-ONNX 离线模型
- 未下载模型时无法播放，会提示前往设置下载
- 内置 VITS / Matcha / Kokoro / Kitten 多语种模型，覆盖中文、中英双语、粤语、英语及德/法/西/葡/俄/韩/越/泰/土/波斯/孟加拉等语言
- 支持多音色模型的 speaker 选择、试听、手动重试下载；下载失败时可使用自定义镜像地址重试
- 中文推荐模型：`vits-piper-zh_CN-huayan-medium`（轻量）或 `vits-zh-hf-theresa` / `vits-melo-tts-zh_en`（高品质）
- 中英双语推荐模型：`matcha-icefall-zh-en`（需额外下载 Vocos 声码器，应用会自动一并获取）或 `kokoro-multi-lang-v1_1`
- 英文推荐模型：`vits-piper-en_US-lessac-medium`
- 合成或播放失败时会在界面显示具体错误信息，详情可查看 logcat（`SherpaOnnxEngine` / `TtsPlaybackService`）
- `scripts/` 下提供模型镜像同步脚本，可将模型文件同步到自建镜像后填入「模型镜像地址」

## 技术栈

- Kotlin + Compose + Material3 + Navigation Compose
- Room + DataStore + WorkManager
- Media3（MediaSession）+ 前台服务 + WakeLock
- OkHttp + NanoHTTPD（URL 下载 / 局域网上传）
- epublib + Jsoup（EPUB 解析）
- Coil（封面加载）
- Sherpa-ONNX（离线 TTS，本地 AAR + 在线下载模型）
- Apache Commons Compress（tar.bz2 模型解压）
