# 知音 · 视频 / 音频 / 文字稿提取器 v0.9

## 修复原因与验证证据

给定的原始分享链接是：
\`https://v.douyin.com/NRlGp9fKoMs/\`

真实跳转作品 ID：\`7410584369103965490\`。

以前版本的关键回退：只用 Android UA 读取网页时，有时只获取到约 32–36KB 的分享页面布局数据，而无 \`videoInfoRes\` 和 \`play_addr\`。这被错误当作不可下载。实际测试发现，使用 iPhone Safari 移动 UA 请求同一个官方公开分享页，可以获得真实的视频信息和播放地址。响应并不完全稳定，故新版一次性按顺序检查两种客户端请求方式。

### 已完成的实际端到端验证（2026-11-12 UTC，GitHub Actions）

1. **真实下载**：[Verify exact Douyin link MP4](../../actions/workflows/verify-user-link.yml)
   - 使用上述完整短链，并确认重定向作品 ID 正确。
   - 从真实 \`aweme.snssdk.com\` 媒体地址完整下载 85,880,112 字节的 MP4。
   - ffprobe 验证含 AAC 音轨、H.264 视频轨道，时长 251.5 秒。
   - 成功运行 ID：\`43328950229\`。
2. **使用 Android 源码原样提取的解析函数测试**：[Verify Android parser with actual Douyin share HTML](../../actions/workflows/verify-android-parser.yml)
   - 从 \`MainActivity.java\` 自动提取 \`jsonObjectFollowing / visitMedia / structuredMedia / scanDirectVideo\` 等真实方法，通过 javac 编译。
   - 使用对应真实分享页数据，确认 Java 解析函数返回有效媒体 URL。
   - 用返回的 URL 下载实际 MP4 前段数据，验证格式字节及 HTTP 206。
   - 成功运行 ID：\`43329580033\`。

**仍未做的验证**：Android 真机运行和用户手机网络下的稳定性。这不是完美成功率的保证。

## App v0.9 改动

- 去掉所有抖音网页打开按钮以及自动外站跳转，App 本身仍以本地 WebView 做 UI。
- 原分享短链优先尝试 Android 和 iPhone Safari 移动 UA，再尝试两个公开分享页；仅在当前任务中自动回退，不要求用户反复点击，不会无限循环。
- 修复 Referer 与公开分享数据加载方式，保留 HTTP Cookie 会话缓存。
- 保留视频 MP4、音频 M4A、SenseVoice Small INT8 完全离线中文转写、TXT/SRT。
- 平台登录/验证码/权限/地区限制依然可能阻止合法访问；不绕过这些限制或 DRM。

## 安装
GitHub Actions \`Build Android APK\`，工件为 \`douyin-export-v09-verified-apk\`，解压得到 \`app-debug.apk\`。Java17 / Kotlin2 / Android SDK35 / AGP8.7.3 / Gradle8.9。首次使用将解压离线中文模型；建议预留至少 600 MB 空间。
