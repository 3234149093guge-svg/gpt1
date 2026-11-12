# 视频音频文字稿提取器（Android v0.1）

安卓 App：抖音分享链接解析（尽力而为）、可访问的 MP4 下载、本地视频导入、AAC 音轨导出 M4A、OpenAI 语音转 TXT。

## 下载 APK
在本仓库点 **Actions → Build Android APK → 最新成功运行 → Artifacts → douyin-export-apk** 下载 ZIP，解压取得 app-debug.apk，在安卓手机上安装。

## 源码编译
项目使用 Java 17、Android Gradle Plugin 8.7.3、Gradle 8.9、SDK 35。编译命令：gradle assembleDebug。

## 限制
- 抖音分享页面常有权限和反爬限制，不能保证直接解析任何链接，不保证无水印，不绕过 DRM 和登录验证。
- 第一版导出的音频为 M4A（原 AAC 封装），不是 MP3。非 AAC 不能导出。
- 文字识别使用用户自行提供的 OpenAI API Key，通过 gpt-4o-mini-transcribe 上传音频，有费用；Key 不保存。音频限制 24MB。
- 当前项目未经过真机功能测试。仅用于处理用户有权保存和转写的文件。
- 这是开发调试 APK（debug 签名），非正式商店发布版本。
