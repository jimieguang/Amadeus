# SenseVoice 本地语音识别配置说明

本项目已将语音识别从百度 SDK 改为 **SenseVoice 本地识别**（基于 sherpa-onnx），无需网络，全部在手机本地运行。

## 一、下载 sherpa-onnx AAR

1. 访问 [sherpa-onnx Releases](https://github.com/k2-fsa/sherpa-onnx/releases)
2. 下载 `sherpa-onnx-1.12.28.aar`（或最新版本的 `.aar` 文件）
3. 将文件放入 `app/libs/` 目录

```
app/
  libs/
    sherpa-onnx-1.12.28.aar
```

若没有 `libs` 目录，请创建。

## 二、下载 SenseVoice 模型

1. 访问 [sherpa-onnx 模型发布页](https://github.com/k2-fsa/sherpa-onnx/releases) 或直接下载：

```bash
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
tar xvf sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
```

2. 将以下文件放入 `app/src/main/assets/sensevoice/`：
   - `model.int8.onnx`
   - `tokens.txt`

```
app/src/main/assets/
  sensevoice/
    model.int8.onnx
    tokens.txt
```

## 三、使用说明

- **主界面**：点击一次开始录音，再点击一次结束录音并识别
- **复读机**：同主界面，说「退出复读」可返回主界面
- **TTS 最小化自检**：在复读机界面长按麦克风图标，直接触发本地 TTS（不走录音与识别）
- 支持中英日韩粤语，自动检测语言

## 四、配置本地 TTS（替代在线 API 音频）

项目已将 `Voice_reply` 改为 **sherpa-onnx 本地 TTS**，不再请求外部 API。

1. 下载 VITS 模型（示例：`vits-melo-tts-zh_en`）

```bash
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2
tar xvf vits-melo-tts-zh_en.tar.bz2
```

2. 将以下文件放入 `app/src/main/assets/tts/vits-melo-tts-zh_en/`：
   - `model.onnx`（或任意 `.onnx` 主模型文件）
   - `lexicon.txt`
   - `tokens.txt`
   - `date.fst`（可选）
   - `number.fst`（可选）

目录结构示例：

```text
app/src/main/assets/
  tts/
    vits-melo-tts-zh_en/
      model.onnx
      lexicon.txt
      tokens.txt
      date.fst
      number.fst
```

首次运行时会自动复制到应用私有目录并进行本地合成。

## 五、构建

```bash
./gradlew assembleDebug
```

安装到手机：

```bash
./gradlew installDebug
```
