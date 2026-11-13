# 知音 视频功能单项测试版 0.6

本分支仅实现一条功能链路：**抖音分享链接 → 获取可用 MP4 → 校验媒体头 → 完整下载到手机 → MediaExtractor 验证视频轨**。

- 没有语音转文字算法、音频导出、模型文件、Kotlin、ONNX、Vosk、OpenAI API 或后台服务器。
- 没有外部网页打开行为；仅使用本地 WebView 展示应用 UI。
- 网络会话由 Java HTTP CookieManager 管理，Android 和 iPhone 分享页元数据按序尝试，两轮上限；不会让用户进入跳转失败页面。
- 每个阶段都有日志；日志记录阶段、作品 ID、域名与结果，但不输出登录 Cookie 和带签名的完整视频地址。
- 候选视频地址会用 HTTP Range 做 MP4 文件头检查；未通过不允许下载。
- 下载结束以 Android MediaExtractor 验证包含视频轨道，确认后保存到「下载/抖音提取器」。
- Android 10 以下保存到应用外部专用下载目录。

## 测试用例
`https://v.douyin.com/NRlGp9fKoMs/`，作品 `7410584369103965490`。

GitHub 的公开网络测试曾实际获取完整 MP4，约 85.9MB，时长约 251.5 秒，H.264 视频与 AAC 音频均通过 FFprobe 检查。但 Android APK 在用户手机环境下能否同样成功还需实测，CI 通过不能替代真机验证。

## 构建
GitHub Actions → **Build Android video-only APK** → 下载 `zhiyin-video-only-v060-apk` → 解压 app-debug.apk。

版本号 0.6 是视频单项验证线的独立编号，不表示以失败的 v0.8 为代码基础。
