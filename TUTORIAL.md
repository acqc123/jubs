# JSub APP - 完整运行教程

## 目录
1. [环境准备](#一环境准备)
2. [导入项目](#二导入项目到-android-studio)
3. [配置API Key](#三配置api-key重要)
4. [构建APK](#四构建apk)
5. [安装到手机](#五安装到安卓手机)
6. [使用APP](#六使用app)
7. [常见问题](#七常见问题排查)

---

## 一、环境准备

### 需要安装的软件

| 软件 | 版本要求 | 下载地址 |
|------|---------|---------|
| Android Studio | 最新版 | https://developer.android.com/studio |
| JDK | 17+ | Android Studio自带 |
| Git | 任意版本 | https://git-scm.com/downloads |

### 1.1 安装 Android Studio

**Windows:**
1. 访问 https://developer.android.com/studio 下载安装包
2. 运行安装程序，全部默认选项即可
3. 安装完成后首次启动，选择 "Standard" 安装类型
4. 等待SDK自动下载完成

**Mac:**
```bash
brew install --cask android-studio
```

**验证安装:**
打开 Android Studio，看到欢迎界面即表示安装成功。

### 1.2 配置 Android SDK

1. 打开 Android Studio
2. 点击右上角 "SDK Manager"（或 `Tools > SDK Manager`）
3. 在 "SDK Platforms" 标签页中勾选：
   - `Android 14.0 (API 34)`
   - `Android 8.0 (API 26)`
4. 在 "SDK Tools" 标签页中确保勾选了：
   - `Android SDK Build-Tools 34`
   - `Android SDK Platform-Tools`
   - `Android Emulator`（如需模拟器测试）
5. 点击 "Apply" 下载

---

## 二、导入项目到 Android Studio

### 2.1 打开项目

**方法一：直接打开（推荐）**
1. 打开 Android Studio
2. 点击 "Open"（不要点 "New Project"）
3. 选择项目文件夹 `/mnt/agents/output/project`（或你复制出来的位置）
4. 等待 Gradle 同步完成（首次可能需要几分钟下载依赖）

**方法二：通过Git克隆**
```bash
git clone <仓库地址> JSub
cd JSub
```
然后用 Android Studio 打开 `JSub` 文件夹。

### 2.2 等待 Gradle 同步

第一次打开项目时，Android Studio 会自动：
- 下载 Gradle 构建工具
- 下载项目依赖库（Kotlin Coroutines, OkHttp等）
- 构建项目索引

> 这个过程可能需要 5-15 分钟，取决于网络速度。看到底部状态栏显示 "Sync finished" 即完成。

### 2.3 验证项目结构

同步完成后，在左侧 Project 视图中应该看到：

```
JSub/
├── app/
│   ├── src/main/java/com/jsub/app/
│   │   ├── MainActivity.kt
│   │   ├── audio/
│   │   ├── api/
│   │   ├── speech/
│   │   ├── service/
│   │   ├── ui/
│   │   ├── model/
│   │   ├── data/
│   │   └── util/
│   ├── src/main/res/
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 三、配置 API Key（重要）

> 语音识别功能 **必须** 配置 OpenAI API Key 才能使用。翻译功能如果不配置 Google Key，会自动使用免费的 LibreTranslate。

### 3.1 获取 OpenAI API Key（用于语音识别）

1. 访问 https://platform.openai.com/api-keys
2. 注册/登录 OpenAI 账号
3. 点击 "Create new secret key"
4. 复制生成的密钥（格式：`sk-xxxxxxxxxxxxxxxx`）
5. **保存好这个密钥，页面关闭后无法再次查看**

### 3.2 获取 Google Translate API Key（可选，用于翻译）

如果不配置，翻译会自动使用免费的 LibreTranslate。

1. 访问 https://cloud.google.com/translate
2. 创建 Google Cloud 项目
3. 启用 Cloud Translation API
4. 创建 API 密钥
5. 复制密钥

### 3.3 在 APP 中配置 API Key

API Key 是在 APP 的设置界面中输入的（不是在代码中硬编码），这样更安全也更灵活。

**方式一：运行后在APP内设置（推荐）**
1. 先构建安装APK（见第四步）
2. 打开APP → 点击右上角设置图标
3. 填入API Key → 保存

**方式二：在代码中预配置（开发测试用）**

如果你想让APP默认就带有API Key，可以修改 `SettingsRepository.kt`：

```kotlin
// 在 SettingsRepository.kt 的 loadSettings() 方法中修改默认值

fun loadSettings(): AppSettings {
    return AppSettings(
        speechApiKey = prefs.getString(KEY_SPEECH_API_KEY, "") 
            ?: "",  // ← 把 "" 改成你的 sk-xxx
        // ...
    )
}
```

**注意：不要把包含API Key的代码提交到公共仓库！**

---

## 四、构建 APK

### 4.1 构建 Debug 版本（测试用）

**方法一：通过 Android Studio 图形界面**
1. 点击顶部菜单 `Build > Build Bundle(s) / APK(s) > Build APK(s)`
2. 等待构建完成（底部显示 "Build successful"）
3. 点击右下角的弹出通知 "locate" 找到 APK 文件
4. APK 路径：`app/build/outputs/apk/debug/app-debug.apk`

**方法二：通过命令行**
```bash
# 在项目根目录下执行
./gradlew assembleDebug

# Windows 用
gradlew.bat assembleDebug
```

构建成功后会显示：
```
BUILD SUCCESSFUL in XXs
APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

### 4.2 构建 Release 版本（正式发布用）

Release 版本需要签名密钥：

```bash
# 1. 生成签名密钥
keytool -genkey -v -keystore jsub-release.keystore -alias jsub -keyalg RSA -validity 10000

# 2. 在 app/build.gradle.kts 中配置签名（见下方说明）

# 3. 构建
./gradlew assembleRelease
```

在 `app/build.gradle.kts` 中添加签名配置：

```kotlin
android {
    // ... existing config ...
    
    signingConfigs {
        create("release") {
            storeFile = file("../jsub-release.keystore")
            storePassword = "你的密钥库密码"
            keyAlias = "jsub"
            keyPassword = "你的密钥密码"
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

---

## 五、安装到安卓手机

### 5.1 通过 USB 安装（推荐开发测试）

**准备:**
1. 手机用USB线连接电脑
2. 手机开启开发者模式：
   - 设置 → 关于手机 → 连续点击"版本号"7次
   - 返回 → 系统和更新 → 开发人员选项 → 开启"USB调试"
3. 手机上允许USB调试授权

**安装:**
```bash
# 检查设备是否连接
adb devices

# 安装APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**通过 Android Studio 安装（最简单）:**
1. 点击顶部工具栏的设备选择下拉框
2. 选择你的手机
3. 点击绿色的 "Run" 按钮（三角形）
4. 等待APP自动编译并安装到手机

### 5.2 手动安装 APK

1. 把 `app-debug.apk` 文件发送到手机（微信、QQ、邮件等）
2. 在手机上点击APK文件
3. 如果提示"未知来源"，去设置中允许安装未知应用
4. 安装完成

---

## 六、使用 APP

### 6.1 首次使用流程

```
┌─────────────────────────────────────────────────────────┐
│  步骤 1: 打开APP                                          │
│  → 看到主界面（紫色主题，有"开启字幕"大按钮）               │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  步骤 2: 配置API Key（强烈建议）                           │
│  → 点击右上角 ⚙️ 设置图标                                  │
│  → 在"语音识别 API Key"中填入 OpenAI 的 sk-xxx 密钥        │
│  → （可选）填入 Google 翻译 API Key                        │
│  → 选择显示模式（双语/仅中文/仅日文）                       │
│  → 调整字体大小和背景透明度                                │
│  → 点击"保存"                                             │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  步骤 3: 开启字幕服务                                      │
│  → 返回主界面                                             │
│  → 点击"开启字幕"按钮                                     │
│  → 弹出权限请求：允许"在其他应用上层显示" → 去设置开启        │
│  → 弹出录屏权限请求：点击"立即开始"                         │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│  步骤 4: 播放日文内容                                      │
│  → 打开任意视频/音频播放器                                  │
│  → 播放日语内容（ASMR、动漫、日剧等）                        │
│  → 悬浮字幕会自动出现在屏幕上！                              │
└─────────────────────────────────────────────────────────┘
```

### 6.2 悬浮窗操作

| 操作 | 说明 |
|------|------|
| 长按拖动 | 按住字幕区域可以拖动到任意位置 |
| 点击 | 显示/隐藏控制按钮 |
| 关闭按钮 | 关闭字幕悬浮窗 |
| 最小化 | 缩小字幕显示区域 |

### 6.3 通知栏

开启字幕后，通知栏会显示常驻通知：
- 标题："JSub 实时日译中字幕"
- 内容："字幕服务运行中"
- 操作按钮："停止字幕" - 点击可直接停止服务

### 6.4 推荐的使用场景

| 场景 | 推荐设置 |
|------|---------|
| 日文ASMR | 显示模式：仅中文；字体大小：14sp；背景透明度：60% |
| 动漫观看 | 显示模式：双语；字体大小：16sp；背景透明度：80% |
| 日剧学习 | 显示模式：双语；字体大小：15sp；背景透明度：85% |
| 日语直播 | 显示模式：仅中文；字体大小：16sp；背景透明度：70% |

---

## 七、常见问题排查

### Q1: Gradle 同步失败，提示 "Could not resolve..."

**原因**: 网络问题导致依赖下载失败

**解决**:
1. 检查网络连接
2. 如果使用国内网络，配置镜像（在 `settings.gradle.kts` 中）：
```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}
```
3. 点击 `File > Sync Project with Gradle Files` 重新同步

### Q2: 构建报错 "compileSdkVersion is not specified"

**原因**: SDK 未正确安装

**解决**:
1. 打开 SDK Manager
2. 确保安装了 Android API 34 的 SDK Platform
3. 重新同步项目

### Q3: APP安装后打开就闪退

**排查步骤**:
1. 连接USB，在 Android Studio 中查看 Logcat 日志
2. 常见原因：
   - 未配置 API Key → 在设置中配置 OpenAI Key
   - 权限未授予 → 检查悬浮窗权限和录屏权限
   - 网络问题 → 确保手机能访问 OpenAI API（需要特殊网络环境）

### Q4: 字幕不显示

**排查步骤**:
1. 检查服务是否运行（通知栏是否有常驻通知）
2. 检查悬浮窗权限是否开启
3. 检查是否正在播放音频/视频
4. 检查API Key是否配置正确
5. 查看 Logcat 中的错误日志

### Q5: 识别准确率不高

**解决**:
1. 确保音频音量足够大
2. 确保播放的是清晰的日语内容
3. 检查网络连接质量（Whisper API需要稳定的网络）
4. 尝试降低背景噪音

### Q6: 翻译速度慢

**解决**:
1. 配置 Google Translate API Key（比免费版快）
2. 检查网络连接
3. 如果持续慢，这是API端点的正常延迟，可以优化代码中的缓冲时间

### Q7: 手机连接电脑后 adb devices 不显示

**解决**:
1. 确保USB线支持数据传输（不仅是充电线）
2. 手机上弹出 "允许USB调试" 时点击确定
3. 尝试更换USB接口
4. 安装手机对应的USB驱动

---

## 八、项目二次开发

如果你想修改或扩展功能：

### 修改语音识别服务
编辑 `api/WhisperApi.kt`，可以切换到其他语音识别服务（如Google Cloud Speech-to-Text）。

### 修改翻译服务
编辑 `api/` 目录下的翻译API实现，或添加新的翻译源。

### 修改字幕样式
编辑 `ui/FloatingSubtitleView.kt` 和 `res/layout/floating_subtitle_view.xml`。

### 添加新功能
项目采用模块化架构，在对应包中添加新类即可，例如：
- 新的音频源 → `audio/` 包
- 新的API集成 → `api/` 包
- 新的处理逻辑 → `speech/` 包

---

## 需要网络环境说明

> **重要**: 本APP使用的语音识别（Whisper）和翻译API的服务器都在海外，国内网络环境可能需要特殊的网络配置才能正常访问。请确保你的网络环境可以访问以下域名：
> - `api.openai.com` (Whisper语音识别)
> - `libretranslate.de` (免费翻译)
> - `translation.googleapis.com` (Google翻译，可选)

---

如有其他问题，请查看项目中的源代码注释，或参考 Android 官方文档。
