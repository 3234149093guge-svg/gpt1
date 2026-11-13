# 知音 v0.5 · 从实际可用的 v0.4 原始代码重新制作

基准提交：`8a8f09107f795ac88cbc73241416f318c2b9ca92`（v0.4）。

这不是在 v0.8 上修补。MainActivity 和 HTML 均直接从 v0.4 提取后做极小修改，保留 v0.4 的以下行为：
- 相同的 Android User-Agent、Referer 与 WebKit CookieManager 读取方法
- 相同的短链重定向、HTML 读取容量与网络超时设置
- 相同的两个备用公开分享页 iesdouyin / douyin
- 相同的 _ROUTER_DATA、RENDER_DATA、play_addr 与 bit_rate 提取逻辑
- 相同的下载 MP4、M4A、SenseVoice 离线转写、TXT/SRT 输出

只变更：
1. 删除打开抖音网页的按钮、外站 WebView 和解析失败后的自动网页跳转。解析不成功时在 App 内明确报告。
2. 为避免重新安装后反复手动点击，解析任务增加并发锁，一次点击最多把 **v0.4 原有完整流程跑两轮**（第一轮不成功时才自动复核）。网络头、解析源和媒体字段提取不变。两轮也未取得播放地址时返回明确失败，不无限重试。

## 验证边界
GitHub Actions 可检查源码、编译和 APK 文件结构，但**不等于真机成功下载**。用户提供的样本 `https://v.douyin.com/NRlGp9fKoMs/` 在 GitHub Runner 的独立网络测试中无法下载：公开分享页未返回媒体信息，yt-dlp 报 403 和缺少新会话 Cookie。因此，这一版是保守回退候选版，不应声称已修复此样本或完成端到端真机验证。

## 构建
Actions → Build Android APK → `zhiyin-v05-classic-from-v04-apk`，下载并解压取得 `app-debug.apk`。Android API 26+、离线转写无 API Key。

## 备注
versionCode=9 以便从更高内部版本平滑覆盖（仍可能因调试证书不同需卸载）。卸载前先备份需要的本地数据。
