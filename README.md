# 知音 Android v0.7 — Qwen3-ASR-0.6B INT8 离线转写

本版本把 Qwen3-ASR-0.6B INT8 真正接入 Android 应用（sherpa-onnx 1.12.34），不是只展示模型介绍。

## 使用流程
1. 到 [Releases](https://github.com/3234149093guge-svg/gpt1/releases/tag/v0.7.0-qwen3-asr-0.6b) 下载 `Zhiyin-Qwen3-ASR-0.6B-INT8-v0.7.apk`。
2. 打开 App → 「下载并安装模型」。官方压缩包约 879 MB，SHA-256 校验成功后解压到应用私有目录；下载中断会保留 `.part` 文件以便重试。
3. 如官方模型下载不通，可先用浏览器下载官方 `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2`，点击「导入已有模型包」，在手机选中压缩包。无需联网即可安装导入的压缩包。
4. 选择本地视频/音频，或者先解析并下载允许访问的抖音视频。
5. 点击「开始离线识别」，生成可编辑中文文字稿，以及按片段时间轴估算的 SRT 字幕。
6. 选择导出 TXT/SRT 到系统下载目录。

## 关键组件
- `app/src/main/java/com/example/douyinexport/QwenModelManager.java`: Android 模型包下载、断点续传、SHA-256、tar.bz2 解压、断电安全临时目录。
- `Qwen3AsrEngine.kt`: Qwen3-ASR-0.6B INT8 Qwen3 模型配置 + sherpa-onnx 离线推理。
- `OfflineTranscriber.java`: Android MediaExtractor / MediaCodec 解码音频，重采样 16kHz 单声道、静音分段、按块推理。
- `MainActivity.java`: 导入视频/音频、视频下载、安装模型、转写任务、TXT/SRT 导出，错误提示。
- `index.html`: Android 内嵌极简界面，无外部网页浏览。
- `build-apk.yml`: Android 构建、验证不捆绑 1 GB 模型、GitHub Release 自动发布 APK。

## 模型与来源
官方镜像 https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2

- 文件大小：878,702,423 bytes
- SHA-256：`393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96`
- sherpa-onnx v1.12.34 AAR SHA-256：`9042637b206ef2dd98ea3a2fe28ffaa7e8767557848d94b5e25c268db188535b`

## 验证边界
APK 成功编译只能证明依赖和 Java/Kotlin 接口能通过构建，**不等于已经在用户手机成功下载并识别音视频**。大模型资源、内存限制以及抖音平台视频解析服务可能影响实际体验。

## 注意事项
- Qwen3-ASR 模型文件超大，建议 8 GB+ 运行内存、2.4 GB 以上可用存储，下载使用 Wi-Fi。
- 安装后语音转文字不上传音频；抖音链接解析需要互联网，并不保证每条公开视频都能获取地址。
- 官方 Qwen3-ASR-0.6B 模型许可和使用条款请参见 QwenLM 官方仓库。
