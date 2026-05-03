package com.jsub.app.speech

import com.jsub.app.util.AudioUtils

/**
 * 语音活动检测结果
 */
data class VadResult(
    /** 是否检测到语音 */
    val hasSpeech: Boolean,
    /** 当段落完成时返回音频段 */
    val audioSegment: ByteArray? = null,
    /** 是否是一个完整段落的结束 */
    val isSegmentEnd: Boolean = false
)

/**
 * 语音活动检测器（VAD）
 *
 * 基于RMS能量阈值的简单VAD实现。
 * 用于检测语音段落边界，决定何时触发语音识别。
 */
class VoiceActivityDetector(
    /** 语音能量阈值 */
    private var energyThreshold: Double = 800.0,
    /** 静音超时（毫秒），超过此时间标记段落结束 */
    private val silenceTimeoutMs: Long = 500,
    /** 最小语音段长度（毫秒） */
    private val minSpeechDurationMs: Long = 300,
    /** 采样率 */
    private val sampleRate: Int = 16000
) {

    private enum class VadState { SILENCE, SPEECH }

    private var state = VadState.SILENCE
    private val buffer = mutableListOf<ByteArray>()
    private var silenceDurationMs = 0L
    private var speechDurationMs = 0L
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0
    private val frameDurationMs = 50L // 每帧50ms

    /**
     * 处理音频数据
     *
     * @param audioBuffer PCM音频数据
     * @return VAD结果
     */
    fun process(audioBuffer: ByteArray): VadResult {
        val energy = AudioUtils.calculateAudioLevel(audioBuffer)
        val isSpeech = energy >= energyThreshold

        when (state) {
            VadState.SILENCE -> {
                if (isSpeech) {
                    consecutiveSpeechFrames++
                    if (consecutiveSpeechFrames >= 2) { // 连续2帧有语音
                        state = VadState.SPEECH
                        buffer.clear()
                        buffer.add(audioBuffer)
                        speechDurationMs = frameDurationMs
                        consecutiveSilenceFrames = 0
                        return VadResult(hasSpeech = true)
                    }
                } else {
                    consecutiveSpeechFrames = 0
                }
                return VadResult(hasSpeech = false)
            }

            VadState.SPEECH -> {
                buffer.add(audioBuffer)
                speechDurationMs += frameDurationMs

                if (isSpeech) {
                    consecutiveSilenceFrames = 0
                    consecutiveSpeechFrames++
                } else {
                    consecutiveSilenceFrames++
                    silenceDurationMs = consecutiveSilenceFrames * frameDurationMs

                    if (silenceDurationMs >= silenceTimeoutMs) {
                        // 静音超时，段落结束
                        val segment = if (speechDurationMs >= minSpeechDurationMs) {
                            buffer.flattenToByteArray()
                        } else null

                        val result = VadResult(
                            hasSpeech = segment != null,
                            audioSegment = segment,
                            isSegmentEnd = true
                        )

                        // 重置状态
                        reset()
                        return result
                    }
                }
                return VadResult(hasSpeech = true)
            }
        }
    }

    /**
     * 强制结束当前段落
     */
    fun forceFinalize(): VadResult {
        val segment = if (buffer.isNotEmpty() && speechDurationMs >= minSpeechDurationMs) {
            buffer.flattenToByteArray()
        } else null

        val result = VadResult(
            hasSpeech = segment != null,
            audioSegment = segment,
            isSegmentEnd = true
        )
        reset()
        return result
    }

    /**
     * 重置检测器状态
     */
    fun reset() {
        state = VadState.SILENCE
        buffer.clear()
        silenceDurationMs = 0
        speechDurationMs = 0
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
    }

    /**
     * 设置新的能量阈值
     */
    fun setThreshold(threshold: Double) {
        energyThreshold = threshold
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
