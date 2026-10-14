二狗叔叔的工具箱 1.0.0

第一个工具：视频转音频
选择视频 → 提取并保存音频 → 选择保存位置 → 试听或分享。

输出为 M4A。AAC 音轨直接提取，保留压缩音频数据；其他系统可解码的音轨转换为 AAC-LC。
选择视频里的第一个音轨。不支持的编码、加密媒体、没有音轨和损坏文件会提示原因。
媒体处理完全在手机上完成。切换应用后可通过前台通知查看进度或取消。
最低 Android 8.0。没有联网权限，也不要求访问全部文件或读取整个媒体库。

构建
安装 JDK17、Android SDK35 与 Build Tools34；配置 JAVA_HOME 和 ANDROID_HOME。
运行 .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
或者使用 PowerShell7 运行 pwsh -File tools/build.ps1
APK 构建完成后，工具脚本会复制到 dist/ErgoushushuToolbox-1.0.0-debug.apk。
使用自己的 release 签名密钥配置正式发布版本；当前交付为可安装的 debug 版本。

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
