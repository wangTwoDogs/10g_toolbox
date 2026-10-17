# 项目约定

## 项目用途

- 本项目用于开发 Android APK。
- APK / 应用名称：**二狗叔叔的工具箱**。
- 初始版本：`1.0.0`。

## 安卓实现

- 原生 Android 工程，Java + Material 3，应用包名：`com.ergoushushu.toolbox`。
- 最低支持 Android 8.0（API 26），当前编译与目标 SDK：35。
- 当前图标使用 `assets/branding/ergoushushu-toolbox-icon-v2.png`。
- 第一个工具：提取视频中的音频，默认保存 M4A。
- 第二个工具：录音压缩为单声道 32 kbps AAC / M4A，保留原采样率、时长和源文件；不分段、不自动上传。
- AAC 音轨直接提取；其他音轨使用手机系统解码器转换为 AAC。设备不支持的格式须明确报错。
- 通过系统文件选择器选择视频及保存位置，媒体处理完全在手机本地完成。
- 提取支持进度、取消、后台处理；失败或取消仅清理本次新建的输出文件。
- 构建使用 JDK 17、Gradle 8.9、Android Gradle Plugin 8.7.3。
- `tools/build.ps1` 默认生成正式签名版本；调试或设备测试需显式使用 `-DebugBuild` 或 `-DeviceTests`。
- 发布密钥 `.signing/toolbox-release.p12` 与 `signing.properties` 仅保存在本机且不进入 Git；后续发布必须复用原密钥。
- 对外安装包名称不带 `debug` 后缀，随正式 GitHub Release 发布。

## 版本管理

- 项目使用 Git 进行版本管理。
- 远程仓库：`https://github.com/wangTwoDogs/10g_toolbox.git`（`origin`）。
- 每次对话完成项目变更后，自动提交本次变更。
