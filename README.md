# 视频、音频、文字稿提取器 v0.4 · 高精度离线版

## 主要升级
本机语音转写从 Vosk Small (42MB) 换成 SenseVoice Small INT8 模型（228MB ONNX），使用 sherpa-onnx 2.0.1 运行。中文普通话及混合英语场景预计比之前效果好，实际准确率取决于录音质量、场景、机型。

- **完全离线**：无需 API Key，音频不上传。
- **视频和音频直接转写**：Android MediaCodec 解码，自动 16kHz 单声道重采样，按停顿分段处理（最长约19秒）。
- **离线标点**：SenseVoice 内置输出（useInverseTextNormalization=true），用户可以编辑修正。
- **TXT / SRT**：导出段落文本和有近似片段时间戳的 SRT 字幕。SRT 时间轴不是逐字时间戳。
- 原有抖音链接解析、MP4 视频保存、M4A 音轨导出功能保留。
- 使用模型与运行库都是本机文件，安装后不联网也能处理已保存的视频文件。

## 运行要求
- Android 8+（API 26），64 位 ARM（或部分32位 ARM 设备）。
- APK 比 v0.3 大得多；解压模型及首次运行建议 **至少600MB空闲空间**。
- 模型运行需要较高 RAM，部分低内存手机可能系统强制退出。长视频的识别速度取决于 CPU。
- **此版本仍需手机真机验证**。如果出现内存不足/崩溃，可退回旧版 APK。

## 构建
GitHub Actions 在编译阶段从上游下载模型与官方编译的 AAR，分别核对 SHA-256。离线识别无需用户联网和 API。
- 模型： [sherpa-onnx SenseVoice INT8 2024-07-17](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2)
- 引擎：[sherpa-onnx v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1)
- 上游参考：[SenseVoice](https://github.com/FunAudioLLM/SenseVoice)
- sherpa-onnx Apache 2.0；模型许可请遵循归档内随附的 LICENSE。

## 限制
抖音链接获取取决于平台访问权限及媒体真实可达性，并非所有链接均能解析。转写可能有同音字和术语错误，无说话人区分，SRT 时间轴为分块近似。不能保证精度达到云端大模型水平。
