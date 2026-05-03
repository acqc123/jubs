package com.jsub.app.speech

import com.jsub.app.model.SubtitleLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 语音处理器状态
 */
enum class ProcessorState {
    /** 空闲 */
    IDLE,
    /** 正在监听音频 */
    LISTENING,
    /** 正在识别语音 */
    RECOGNIZING,
    /** 正在翻译 */
    TRANSLATING,
    /** 发生错误 */
    ERROR
}

/**
 * 语音处理器接口
 *
 * 负责协调音频流的语音识别和翻译处理，输出字幕数据流。
 */
interface SpeechProcessor {

    /**
     * 开始处理音频流
     *
     * @param audioFlow PCM音频数据流（16kHz, 16bit, 单声道）
     */
    fun startProcessing(audioFlow: Flow<ByteArray>)

    /**
     * 停止处理
     */
    fun stopProcessing()

    /**
     * 字幕数据流
     */
    val subtitleFlow: Flow<SubtitleLine>

    /**
     * 当前处理状态
     */
    val stateFlow: StateFlow<ProcessorState>
}
