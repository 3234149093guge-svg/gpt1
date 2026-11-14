# 知音 v0.7.2 · Qwen3-ASR-0.6B INT8 后台识别

Release: https://github.com/3234149093guge-svg/gpt1/releases/tag/v0.7.2-qwen3-asr-background

## 本版更新
- 改为 Android **Foreground Service**，在用户点击“开始后台识别”后运行；切换到其他 App 或知音页面销毁，不会由 Activity 自动取消。
- Android 15+ 使用 mediaProcessing 类型，Android 14 使用 dataSync 类型（本地文件处理）；Android 13+ 申请通知权限。显示持续通知，可通过通知栏取消任务。
- 识别完成的每段文字会同步原子写入 JSON 检查点，包含 TXT 和 SRT；崩溃/被系统终止时，用户下次打开可查看先前已完成段落。
- App 切回前台自动恢复状态、文字稿、字幕与耗时信息。
- **恢复 v0.7 默认固定 2 CPU 线程**，同一 Qwen3 0.6B INT8 ONNX 模型、同一静音分段及推理参数，不使用 GPU/NPU，也不削减精度。
- 本地音视频暂存从 Android cache 目录改为应用 files 目录，降低被系统缓存清理影响的风险。
- 使用独立应用 ID com.example.douyinexport.background（考虑 GitHub 调试签名每次构建不同），不覆盖你已有的 v0.7/v0.7.1。安装本版后需要在本版中导入或下载模型，官方压缩包可通过手机 Download 目录重复使用。

## 使用步骤
1. 安装 APK，允许通知权限。
2. 点击本地语音模型 → 导入已有模型包（tar.bz2）或下载安装模型。
3. 导入本地视频 / 音频，或解析下载视频，点击【开始后台识别】。
4. 可以切换至微信、抖音等其他 App。通过通知栏确认当前仍在转写，并可停止识别。
5. 返回知音：自动显示已完成片段及进度；若系统强制终止，则自动显示已保存的部分内容。

## 限制与验证
- 2026 年 Android 对后台任务仍有平台限制，Foreground Service 提高存活概率，**不能保证永不被 OEM 杀后台**。
- 任务中断后 **不会自动恢复模型推理进度**，只恢复/保留已经完成的段落文字。
- 模型仍需约 879MB 压缩包下载，离线推理。请在老版已安装且正常时保留旧版，不必卸载。
- GitHub Actions 验证的是 Android 构建，不是荣耀 Magic7 真机的后台运行结果。建议先测试 4 分钟视频、切换微信一两分钟，再回到知音。
