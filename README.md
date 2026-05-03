# JSub - 实时日译中悬浮字幕APP

一款Android实时字幕翻译应用，捕获设备播放的音频，识别日语语音并翻译为简体中文，以悬浮窗形式显示字幕。专为日文ASMR、动漫、日剧等场景设计。

---

## 功能特点

- 实时捕获系统音频（视频/音频播放器）
- 日语语音识别（基于OpenAI Whisper）
- 日译中实时翻译（Google翻译 / LibreTranslate免费版）
- 悬浮窗字幕显示，支持拖动定位
- 双语/仅中文/仅日文 三种显示模式
- 字幕样式自定义（字体大小、背景透明度）
- 基于VAD的语音段落智能检测
- 低延迟流式处理

---

## 系统要求

- Android 8.0+ (API 26)
- 网络连接（用于语音识别和翻译API）
- 录屏权限（MediaProjection，用于捕获音频）
- 悬浮窗权限

---

## 项目结构

```
app/src/main/java/com/jsub/app/
├── JSubApplication.kt              # Application类（通知通道初始化）
├── MainActivity.kt                 # 主界面（权限管理、服务控制）
├── SubtitleSettingsActivity.kt     # 设置界面
├── audio/
│   ├── AudioCapturer.kt            # 音频捕获接口
│   └── SystemAudioCapturer.kt      # 系统音频捕获（MediaProjection）
├── api/
│   ├── TranslationApi.kt           # 翻译API接口
│   ├── GoogleTranslateApi.kt       # Google翻译实现
│   ├── LibreTranslateApi.kt        # LibreTranslate免费翻译
│   ├── SpeechRecognitionApi.kt     # 语音识别接口
│   └── WhisperApi.kt               # OpenAI Whisper实现
├── speech/
│   ├── SpeechProcessor.kt          # 语音处理器接口
│   ├── StreamingSpeechProcessor.kt # 流式处理引擎
│   └── VoiceActivityDetector.kt    # 语音活动检测（VAD）
├── service/
│   └── FloatingSubtitleService.kt  # 悬浮字幕前台服务
├── ui/
│   ├── FloatingSubtitleView.kt     # 悬浮窗字幕视图
│   └── SettingsViewModel.kt        # 设置ViewModel
├── model/
│   ├── SubtitleLine.kt             # 字幕数据模型
│   └── AppSettings.kt              # 应用设置模型
├── data/
│   └── SettingsRepository.kt       # 设置数据存储（SharedPreferences）
└── util/
    └── AudioUtils.kt               # 音频工具（RMS计算、重采样等）
```

---

## 技术架构

```
[MediaProjection] → [AudioRecord] → PCM ByteArray Flow
                                          |
                                          v
                              [VoiceActivityDetector]
                                          |
                                          v
                              [WhisperApi] → 日文文本
                                          |
                                          v
                              [TranslationApi] → 中文文本
                                          |
                                          v
                              [FloatingSubtitleView] 显示
```

---

## 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd JSub
```

### 2. 配置API Key（可选）

首次启动应用后，进入设置页面：

- **语音识别API Key**: [OpenAI API Key](https://platform.openai.com/api-keys)
  - 用于Whisper语音识别
  - 不配置则无法使用语音识别功能

- **翻译API Key**: [Google Cloud API Key](https://cloud.google.com/translate)（可选）
  - 如不提供，自动使用免费的LibreTranslate
  - 免费翻译可能有速率限制

### 3. 构建APK

```bash
./gradlew assembleDebug
```

APK输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### 4. 安装运行

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 使用说明

1. **授予权限**：首次打开APP，依次授予悬浮窗权限和录屏权限
2. **配置API Key**（推荐）：进入设置页面，输入OpenAI API Key
3. **开启字幕**：返回主界面，点击"开启字幕"按钮
4. **播放日文内容**：打开任意视频或音频播放器播放日文内容
5. **查看字幕**：悬浮字幕会自动出现在屏幕上
6. **调整位置**：长按拖动字幕到任意位置
7. **自定义样式**：进入设置可调整显示模式、字体大小等

---

## API说明

### 语音识别
- **服务**: OpenAI Whisper
- **端点**: `https://api.openai.com/v1/audio/transcriptions`
- **模型**: whisper-1
- **语言**: ja（日语）

### 翻译服务
| 服务 | 类型 | 说明 |
|------|------|------|
| Google Translate | 付费 | 需要API Key，速度快质量高 |
| LibreTranslate | 免费 | 无需Key，可能有速率限制 |

---

## 技术细节

### 音频处理
- **采样率**: 16kHz, 16bit, 单声道
- **缓冲区**: 每500ms推送一个音频块
- **VAD阈值**: 600.0（RMS能量）
- **静音超时**: 600ms

### 流式处理
- **中间结果触发**: 3秒语音触发临时识别
- **最终确认**: 静音超时后确认段落结束
- **内存保护**: 缓冲区上限960KB

### 悬浮窗
- **类型**: TYPE_APPLICATION_OVERLAY
- **默认位置**: 屏幕底部偏上
- **拖动阈值**: 15px

---

## 注意事项

1. **录屏权限**: 这是捕获系统音频的必要权限，不会录制屏幕画面
2. **前台服务**: 字幕服务以前台服务运行，通知栏会显示常驻通知
3. **网络消耗**: 语音识别和翻译需要联网，持续使用会产生API调用费用
4. **免费模式**: 不配置API Key时，翻译使用免费的LibreTranslate，但语音识别仍需OpenAI Key
5. **电池优化**: 建议将应用加入电池优化白名单，避免后台被杀

---

## 开源协议

MIT License
