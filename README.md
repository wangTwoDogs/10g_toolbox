# 二狗叔叔的工具箱

![二狗叔叔的工具箱图标](assets/branding/previews/icon-v2-64.png)

我受够了那些号称免费的工具：钱是不用花，垃圾广告却一大堆。于是我决定自己做一个——二狗叔叔的工具箱。

先从视频提取音频开始。以后我需要什么工具，就往里面补什么。

等我哪天买彩票发财了，再加一些需要调用外部接口才能实现的好功能。

## 下载与安装

[下载最新正式版](https://github.com/wangTwoDogs/10g_toolbox/releases/latest)

在 Release 的 **Assets** 中下载 `.apk` 文件，传到安卓手机安装即可。支持 **Android 8.0 及以上**。

如果安装过之前的 debug 测试版，需要先卸载，再安装正式版。后续正式版本会复用同一发布签名。

## 现在能做什么

### 提取视频中的音频

选择视频 → 提取并保存音频 → 选择保存位置 → 试听或分享。

- 保存为 **M4A**。AAC 音轨直接提取，保留原始压缩音频数据；其他手机支持的音轨转换为 AAC。
- 支持进度显示、取消和后台提取。
- 全部在手机本地处理，视频不会上传，也不用看广告。

目前提取视频中的第一个音轨。没有音轨、文件损坏、加密媒体或手机不支持的格式，会提示原因。

### 压缩录音

切换到「录音压缩」→ 一次选择多份录音 → 选择保存文件夹 → 开始批量压缩。

- 重新编码为 **M4A（AAC、单声道、32 kbps、采样率最高 16 kHz）**，每小时约 14 MB，实际大小会随文件略有浮动。
- 适合人声录音及网络传输，属于有损压缩；高采样率录音先滤波再降采样，减少编码量，不改变时长和播放速度，不裁剪或分段。
- WAV / PCM 直接送入编码器，绕过多余的原始音频解码器；压缩优先使用系统软件 AAC 编码器，避免依赖厂商的实时编码路径。
- 原文件保持不变，压缩文件另存为带「-压缩」后缀的新文件，并显示压缩前后的大小。
- 原文件大小可读取时，如果压缩结果没有变小，会提示继续使用原文件，不保存更大的结果。
- 后台依次处理，允许继续添加录音；单份失败会继续下一份。已完成文件可以立即试听或分享，停止队列时保留已完成文件。
- 记录保存在本机，重新打开仍能查看；可以按状态筛选、勾选多个结果后批量分享。系统分享菜单中的接收应用自行决定是否支持多文件。
- 如果应用被系统终止，记录会标明中断并提供重试，清理该任务创建的未完成文件，不会默默丢失队列状态。
- 完全在手机本地完成，不会自动上传或提交到任何平台。
- 格式支持取决于手机系统的解码器；文件损坏或设备不支持时会明确提示。

## 开发与构建

原生 Android 工程，使用 Java 和 Material 3。构建环境：JDK 17、Android SDK 35、Build Tools 34、PowerShell 7。项目已包含 Gradle Wrapper。

配置 `JAVA_HOME` 和 `ANDROID_HOME` 后，运行：

```powershell
pwsh -File tools/build.ps1
```

默认生成正式签名版本，输出到 `dist/ErgoushushuToolbox-<版本号>.apk`，同时生成 `.sha256` 校验文件。下载的校验文件可以和 `Get-FileHash -Algorithm SHA256` 的结果对照。

首次发布可运行 `pwsh -File tools/init-signing.ps1` 创建本机固定发布密钥。后续版本必须复用原密钥，请保留 `.signing/toolbox-release.p12` 和 `signing.properties` 的安全备份；这些文件不进入 Git，也不随 Release 上传。

开发调试时添加 `-DebugBuild`；需要使用当前会话的 `HTTP_PROXY` / `HTTPS_PROXY` 下载构建依赖时，可添加 `-UseSessionProxy`。

### 测试

连接启用 USB 调试的安卓手机，或启动模拟器后，指定测试设备：

```powershell
pwsh -File tools/build.ps1 -DeviceTests -DeviceSerial emulator-5554
```

设备测试使用调试构建，测试结果保存在 `dist/instrumentation-results.txt`。测试在独有缓存目录生成媒体素材，结束后精确清理。

测试覆盖真实视频提取、AAC 压缩数据保持、转换后解码、音轨时长、无音轨、损坏文件、取消和前台服务。已有验证记录见 [TESTING.txt](TESTING.txt)。

### 技术资料

- [MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor)
- [MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer)
- [系统文件选择器与文档存储](https://developer.android.com/training/data-storage/shared/documents-files)
- [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)
