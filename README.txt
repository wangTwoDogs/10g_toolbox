二狗叔叔的工具箱 1.0.0

第一个工具：视频转音频
选择视频 → 提取并保存音频 → 选择保存位置 → 试听或分享。

输出为 M4A。AAC 音轨直接提取，保留压缩音频数据；其他系统可解码的音轨转换为 AAC-LC。
选择视频里的第一个音轨。不支持的编码、加密媒体、没有音轨和损坏文件会提示原因。
媒体处理完全在手机上完成。切换应用后可通过前台通知查看进度或取消。
最低 Android 8.0。没有联网权限，也不要求访问全部文件或读取整个媒体库。

构建
安装 JDK17、Android SDK35 与 Build Tools34；配置 JAVA_HOME 和 ANDROID_HOME。
使用 PowerShell7 运行 pwsh -File tools/build.ps1，默认生成正式签名版本。
APK 构建完成后，工具脚本会复制到 dist/ErgoushushuToolbox-1.0.0.apk，并生成 SHA256 校验文件。
首次发布可运行 pwsh -File tools/init-signing.ps1 创建本机固定发布密钥。
后续所有版本必须复用该密钥。保留 .signing/toolbox-release.p12 和 signing.properties 的安全备份。
签名文件与密码不进入 Git，也不随 Release 上传。
开发调试时显式添加 -DebugBuild；设备测试使用调试构建。

下载与安装
正式安装包从 https://github.com/wangTwoDogs/10g_toolbox/releases 下载。
如已安装之前的 debug 测试版，需要先卸载再安装正式版；以后正式版本可使用相同签名更新。

测试
连接安卓手机（启用 USB 调试）或已启动的模拟器后：
pwsh -File tools/build.ps1 -DeviceTests -DeviceSerial emulator-5554
脚本编译测试包，通过 adb 在设备上执行 AndroidJUnitRunner，并记录 dist/instrumentation-results.txt。
如果下载依赖需要使用当前会话的 HTTP_PROXY / HTTPS_PROXY，可添加 -UseSessionProxy。
测试在独有缓存目录生成媒体夹具，结束后精确删除。
覆盖真实视频提取、AAC 压缩数据保持、WAV 转 AAC 后解码、音轨时长、无音轨、损坏文件、取消及前台服务。

官方技术资料
https://developer.android.com/reference/android/media/MediaExtractor
https://developer.android.com/reference/android/media/MediaMuxer
https://developer.android.com/training/data-storage/shared/documents-files
https://developer.android.com/develop/background-work/services/fgs/service-types
