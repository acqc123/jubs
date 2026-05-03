# JSub v3 全面Bug修复计划

## 已确认的致命Bug

### Bug #1: Service启动无异常保护 → 进程整体崩溃 ⭐⭐⭐ CRITICAL
**位置**: `FloatingSubtitleService.handleStart()` / `onStartCommand()`
**症状**: 点击"开启字幕"后APP"立刻最小化"，重新打开显示"服务未运行"
**根因**: `handleStart()` 方法**没有任何try-catch**。任何一步失败（音频初始化、引擎创建、模型加载等）都会抛出未捕获异常，导致**整个APP进程被系统杀死**（Activity+Service一起死）。

### Bug #2: Service启动顺序错误 → startForeground超时/崩溃 ⭐⭐⭐ CRITICAL
**位置**: `FloatingSubtitleService.handleStart()`
**症状**: 服务启动后系统强制停止
**根因**: `startForegroundService()` 被调用后，**必须在5秒内**调用 `startForeground()`。当前代码先做了MediaProjection初始化、悬浮窗创建、引擎创建等大量操作，之后才调`startForeground()`。如果前面任何一步卡住或崩溃，就违反了前台服务规则，系统直接ANR并杀死服务。

### Bug #3: 设置页面初始化无保护 → Activity崩溃 ⭐⭐ CRITICAL
**位置**: `SubtitleSettingsActivity.onCreate()`
**症状**: 点击设置图标后APP直接最小化/消失
**根因**: Activity创建过程中，`initViews()` + `observeViewModel()` + 异步`loadSettings()`组合，如果ViewModel初始化或布局加载有任何异常，Activity直接崩溃销毁。

### Bug #4: SystemAudioCapturer API版本兼容 ⭐⭐ MEDIUM
**位置**: `SystemAudioCapturer.startCapture()`
**根因**: 使用`AudioPlaybackCaptureConfiguration`（API 29+），低版本Android会崩溃。需要添加版本检查。

### Bug #5: SenseVoice模型初始化阻塞主线程 ⭐⭐ MEDIUM
**位置**: `SenseVoiceEngine.initialize()` / `FloatingSubtitleService.handleStart()`
**根因**: Service的`handleStart()`在主线程执行，如果调用`SenseVoiceEngine.initialize()`下载200MB模型，会长时间阻塞主线程，导致ANR或前台服务超时。

---

## 修复方案

### 修复1: 重构Service启动流程（最关键）

```
新的启动顺序：
1. onStartCommand() → 立即startForeground()（保证5秒内完成）
2. 所有后续操作放入协程（后台线程）
3. 全面try-catch保护
4. 分阶段启动：先显示悬浮窗，再初始化引擎
```

### 修复2: SubtitleSettingsActivity全面try-catch
- `onCreate()` 包裹try-catch
- `initViews()` 每个findViewById检查null
- ViewModel创建加保护

### 修复3: SystemAudioCapturer版本检查
- API < 29 时降级为麦克风录音
- 或提示用户需要Android 10+

### 修复4: 引擎初始化异步化
- Service启动时不立即初始化SenseVoice
- 先启动服务+显示悬浮窗，后台下载/加载模型
- 模型准备好后再开始语音识别

---

## 执行计划

**阶段1**: 修复Service启动流程（FloatingSubtitleService.kt）
**阶段2**: 修复SystemAudioCapturer兼容性和异常保护
**阶段3**: 修复SubtitleSettingsActivity异常保护
**阶段4**: 修复MainActivity异常处理
**阶段5**: 推送GitHub + 编译 + 测试验证
