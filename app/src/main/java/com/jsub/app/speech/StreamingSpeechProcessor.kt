package com.jsub.app.speech

import android.content.Context
import android.util.Log
import com.jsub.app.api.DeepSeekTranslationApi
import com.jsub.app.api.GoogleTranslateApi
import com.jsub.app.api.KimiTranslationApi
import com.jsub.app.api.LibreTranslateApi
import com.jsub.app.api.TranslationApi
import com.jsub.app.model.DisplayMode
import com.jsub.app.model.SpeechProvider
import com.jsub.app.model.SubtitleLine
import com.jsub.app.model.TranslationProvider
import com.jsub.app.speech.engine.EngineFactory
import com.jsub.app.speech.engine.SpeechRecognitionEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

/**
 * 流式语音处理器实现
 *
 * 核心处理引擎，负责：
 * 1. 接收音频流并进行VAD语音活动检测
 * 2. 对语音段调用语音识别引擎（日文）
 * 3. 对识别结果调用翻译API（日→中）
 * 4. 输出字幕数据流
 *
 * 支持中间结果（isFinal=false）和最终结果（isFinal=true）。
 * 支持多种语音识别引擎（Whisper / SenseVoice本地 / AnimeWhisper）。
 */
class StreamingSpeechProcessor(
    private val speechEngine: SpeechRecognitionEngine,
    private val translationApi: TranslationApi
) : SpeechProcessor {

    /**
     * 使用设置构建处理器的工厂方法
     */
    companion object {
        private const val TAG = "StreamingSpeechProcessor"
        private const val INTERIM_TIMEOUT_MS = 3000L // 3秒触发中间结果
        private const val MAX_BUFFER_SIZE = 960000 // 最大缓冲区 ~30秒音频

        fun create(
            context: Context,
            speechProvider: SpeechProvider,
            speechApiKey: String,
            translationApiKey: String,
            translationProvider: TranslationProvider
        ): StreamingSpeechProcessor {
            val speechEngine = EngineFactory.createEngine(context, speechProvider, speechApiKey)
            val translationApi: TranslationApi = when (translationProvider) {
                TranslationProvider.GOOGLE_TRANSLATE -> GoogleTranslateApi(translationApiKey)
                TranslationProvider.LIBRE_TRANSLATE -> LibreTranslateApi()
                TranslationProvider.DEEPSEEK -> DeepSeekTranslationApi(translationApiKey)
                TranslationProvider.KIMI -> KimiTranslationApi(translationApiKey)
            }
            return StreamingSpeechProcessor(speechEngine, translationApi)
        }
    }

    private val vad = VoiceActivityDetector(
        energyThreshold = 600.0,
        silenceTimeoutMs = 600,
        minSpeechDurationMs = 250
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null

    private val _subtitleFlow = MutableSharedFlow<SubtitleLine>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val subtitleFlow: Flow<SubtitleLine> = _subtitleFlow.asSharedFlow()

    private val _stateFlow = MutableStateFlow(ProcessorState.IDLE)
    override val stateFlow: StateFlow<ProcessorState> = _stateFlow.asStateFlow()

    // 内部音频缓冲区，用于中间结果
    private var interimBuffer = mutableListOf<ByteArray>()
    private var interimBytesCount = 0
    private var lastInterimTime = 0L

    override fun startProcessing(audioFlow: Flow<ByteArray>) {
        stopProcessing()

        processingJob = scope.launch {
            _stateFlow.value = ProcessorState.LISTENING
            lastInterimTime = System.currentTimeMillis()

            audioFlow.collect { audioChunk ->
                try {
                    processAudioChunk(audioChunk)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing audio chunk", e)
                    _stateFlow.value = ProcessorState.ERROR
                    _subtitleFlow.emit(
                        SubtitleLine(
                            japaneseText = "",
                            chineseText = "[处理错误: ${e.localizedMessage}]",
                            timestamp = System.currentTimeMillis(),
                            isFinal = true
                        )
                    )
                    delay(500)
                    _stateFlow.value = ProcessorState.LISTENING
                }
            }
        }

        Log.i(TAG, "Speech processing started with engine: ${speechEngine.name}")
    }

    override fun stopProcessing() {
        processingJob?.cancel()
        processingJob = null

        // 强制处理剩余缓冲
        try {
            val vadResult = vad.forceFinalize()
            if (vadResult.audioSegment != null) {
                scope.launch {
                    processSpeechSegment(vadResult.audioSegment, isFinal = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error finalizing", e)
        }

        interimBuffer.clear()
        interimBytesCount = 0
        _stateFlow.value = ProcessorState.IDLE
        Log.i(TAG, "Speech processing stopped")
    }

    /**
     * 处理单个音频块
     */
    private suspend fun processAudioChunk(audioChunk: ByteArray) {
        // 累积到中间结果缓冲区
        interimBuffer.add(audioChunk)
        interimBytesCount += audioChunk.size

        // VAD处理
        val vadResult = vad.process(audioChunk)

        // 检查是否需要触发中间结果（语音超过3秒）
        val now = System.currentTimeMillis()
        if (vadResult.hasSpeech &&
            now - lastInterimTime > INTERIM_TIMEOUT_MS &&
            interimBytesCount > 96000 // 至少3秒音频
        ) {
            lastInterimTime = now
            val interimAudio = interimBuffer.flattenToByteArray()
            processSpeechSegment(interimAudio, isFinal = false)
            // 清空但不重置，继续累积
            interimBuffer.clear()
            interimBuffer.add(audioChunk)
            interimBytesCount = audioChunk.size
        }

        // VAD检测到完整段落
        if (vadResult.isSegmentEnd && vadResult.audioSegment != null) {
            interimBuffer.clear()
            interimBytesCount = 0
            processSpeechSegment(vadResult.audioSegment, isFinal = true)
        }

        // 内存保护：缓冲区过大时强制处理
        if (interimBytesCount > MAX_BUFFER_SIZE) {
            Log.w(TAG, "Buffer overflow protection triggered")
            val forcedAudio = interimBuffer.flattenToByteArray()
            interimBuffer.clear()
            interimBytesCount = 0
            processSpeechSegment(forcedAudio, isFinal = true)
        }
    }

    /**
     * 处理语音段：识别+翻译
     */
    private suspend fun processSpeechSegment(audioData: ByteArray, isFinal: Boolean) {
        if (audioData.size < 3200) return // 忽略小于100ms的音频

        // 语音识别
        _stateFlow.value = ProcessorState.RECOGNIZING
        val recognitionResult = speechEngine.recognize(audioData)

        if (recognitionResult.isBlank() || recognitionResult.startsWith("[")) {
            // 识别失败或无内容
            return
        }

        // 翻译
        _stateFlow.value = ProcessorState.TRANSLATING
        val translatedText = try {
            translationApi.translate(recognitionResult)
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed", e)
            "[翻译失败]"
        }

        // 输出字幕
        val subtitle = SubtitleLine(
            japaneseText = recognitionResult,
            chineseText = translatedText,
            timestamp = System.currentTimeMillis(),
            isFinal = isFinal
        )

        _subtitleFlow.emit(subtitle)
        _stateFlow.value = ProcessorState.LISTENING

        Log.d(TAG, "Subtitle ${if (isFinal) "FINAL" else "interim"}: $recognitionResult -> $translatedText")
    }

    private fun MutableList<ByteArray>.flattenToByteArray(): ByteArray {
        val totalSize = sumOf { it.size }
        val result = ByteArray(totalSize)
        var pos = 0
        for (arr in this) {
            System.arraycopy(arr, 0, result, pos, arr.size)
            pos += arr.size
        }
        return result
    }
}
