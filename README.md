# 视频 · 音频 · 文字稿提取器（Android v0.3 离线版）

安卓 App：支持抖音分享链接解析（在平台允许时）、视频 MP4 保存、视频或音频本地导入、AAC 音轨导出 M4A、**本机中文语音转写**，导出 **TXT 和 SRT**。

## 完全离线中文识别
- 内置 Vosk `vosk-model-small-cn-0.22`，无需 OpenAI API Key，不会向外部语音识别服务发送音频。
- 首次使用自动解压 APK 内模型到应用私有目录；无需下载模型，但需要较多剩余磁盘空间。
- 使用 Android MediaExtractor / MediaCodec 解码音频，转为 16kHz、单声道 PCM。识别时逐块处理，无需一次性加载整段视频。
- 输出分段文字，按语音停顿添加近似标点、带时间轴的 SRT 字幕，支持编辑和复制。
- Vosk 轻量中文模型识别错误率比大型云端模型高，尤其是方言、嘈杂背景、音乐、专业名词；规则标点不能替代深度标点恢复。
- 较长视频转写需要较长时间，部分手机内存/电量压力较大。建议在后台关闭其他大型应用，使用充电器。
- 纯端侧中文模型仅覆盖中文及有限混合词汇。

## 构建 APK
本仓库 [Actions](../../actions) → Build Android APK → 最新成功构建 → 下载 **douyin-export-offline-v03-apk** → 解压取得 `app-debug.apk`。

Java 17 / AGP 8.7.3 / Gradle 8.9 / Android SDK 35。构建过程中自动从模型官方站点下载 **Apache 2.0** 中文模型，将其内置于 APK；安装后即可断网转写。

代码使用了 Vosk Android AAR（Apache 2.0），来源：
- https://github.com/alphacep/vosk-api
- https://alphacephei.com/vosk/models
- 模型：`vosk-model-small-cn-0.22`
- 许可：https://www.apache.org/licenses/LICENSE-2.0

## 已知限制
- 抖音网页可能需要登录，反爬或媒体权限会阻止获取视频；不能保证任意链接解析成功，不承诺无水印。
- 视频音频保存前确认自己拥有处理权。没有绕过 DRM 或登录权限机制。
- 当前音频导出为 M4A（AAC 无损重封装），不是 MP3，非 AAC 音轨会提示不支持。
- 本项目编译为 debug APK，功能在真实设备上仍需验证。
