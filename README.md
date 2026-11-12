# 知音 · 视频/音频/文字稿 v0.8 稳定解析修复版

## 解决两项明确需求
1. **不要网页打开功能**：不显示“打开网页”按钮，也不加载抖音外站 WebView，不会把用户跳转到不可用的外站。应用仍使用本地 WebView 显示自己的页面，并通过 HTTPS 请求读取公开的视频信息。
2. **首次安装需要手动反复解析**：一次点击包含 HTTP 会话初始化、共享 CookieStore，以及最多两轮有上限的自动解析。只有首次使用才进行连接预热，后续同一进程沿用 CookieStore。遇 HTTP 429 时停止自动复核，避免继续限流。

## 技术调整
- 解析主干延续 v0.4：原分享链接 → iesdouyin 公开分享页 → douyin 公开分享页。
- 保留 v0.4 Android UA、Referer 处理和网页 HTML 上限，不再使用 v0.7 的 iPhone UA 或额外实验性公开详情 API。
- HttpURLConnection 使用 java.net.CookieManager 保存响应 Set-Cookie；旧实现仅从 WebView CookieManager 读取 Cookie，普通 HTTP 访问获得的 Cookie 未必能在下次请求复用。这是针对首次安装重复失败的潜在原因采取的修复，**尚无真机确认该原因是唯一根因**。
- 有限的自动重试（2 轮）代替用户多次点“解析”；不会无限循环，不会强制打开网页。
- 每次返回无法解析时展示当前原因及对照信息；对没有返回播放数据的作品不宣称已删除。
- 内置 SenseVoice Small INT8 离线中文转写及视频 MP4、音频 M4A、TXT/SRT 输出完整保留，无任何转写 API Key。

## 验证状态
GitHub Actions 只验证 Android 源码编译和 APK 中的本地模型；尚需同机、同网络针对之前会失败的链接做真机 A/B 测试。
如果平台仅返回验证页面或者需要登录，普通 HTTP 请求无法保证获取视频资源；本 App 不绕过平台访问限制、认证及 DRM。
安装开发调试 APK 时可能因签名不同需要卸载旧版，请先保存重要文字稿。

## 构建
GitHub Actions / Build Android APK / `douyin-export-v08-session-stable-apk`，解压即 `app-debug.apk`。
