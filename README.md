# Amadeus

基于原版 Amadeus Android 项目进行二次开发，当前版本重点完成了本地语音链路替换与功能扩展。

## 项目说明

- 原项目来源：`Yink/Amadeus`
- 当前分支方向：将语音识别改为本地可用方案，并逐步增强交互能力
- 项目技术栈：Android (Java/Kotlin) + Gradle + JNI/CMake + sherpa-onnx + VITS

## 当前主要特性

- 本地语音识别（ASR）：基于 SenseVoice / sherpa-onnx
- 本地语音合成（TTS）：基于 VITS + JNI
- 对话主界面与复读模式
- 闹钟唤起联动
- 偏好设置（模型、字幕、通知等）

## 首次构建前准备

语音识别相关 AAR 与模型文件需先准备完成，再进行编译运行：

- 详细步骤请看：[`SENSEVOICE_SETUP.md`](SENSEVOICE_SETUP.md)
- 相关资源位于：`app/libs`、`app/src/main/assets`
- 大文件（模型与 APK）统一通过 GitHub Release 下载，不直接存放在 Git 历史中

## 本地构建

```bash
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug
```

## 目录概览

- `app/src/main/java`：应用主逻辑
- `app/src/main/cpp`：JNI 与 native 构建脚本
- `app/src/main/assets`：ASR/TTS 模型与资源
- `app/src/main/res`：布局、字符串、偏好配置

## 说明

- 本仓库不提交构建缓存与临时产物（如 `build`、`.cxx`、`app/release`）
- 若需要发布 APK，请在本地构建后自行导出并上传至 Release
- 以下大文件已改为 Release 分发：
  - `app/src/main/assets/sensevoice/model_int8.onnx`
  - `app/src/main/assets/tts/vits-melo-tts-zh_en/model.onnx`
  - `app/src/main/assets/open_jtalk_dic_utf_8-1.11/sys.dic`
  - `app/release/app-release.apk`

## 致谢

感谢原项目与社区贡献者的基础工作。
