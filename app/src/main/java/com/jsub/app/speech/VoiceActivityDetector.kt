package com.jsub.app.speech

import com.jsub.app.util.AudioUtils

/**
 * 语音活动检测结果
 */
data class VadResult(
    val hasSpeech: Boolean,
    val audioSegment: ByteArray? = null,
    val isSegmentEnd: Boolean = false
)

/**
 * 语音活动检测器（VAD）
 *
 * 基于RMS能量阈值的简单VAD实现。
 */
class VoiceActivityDetector(
    /** 语音能量阈值 - 降低到300以检测ASMR/视频音频 */
    private var energyThreshold: Double = 300.0,
    private val silenceTimeoutMs: Long = 800,
    private val minSpeechDurationMs: Long = 400,
    private val sampleRate: Int = 16000
) {

    private enum class VadState { SILENCE, SPEECH }

    private var state = VadState.SILENCE
    private val buffer = mutableListOf<ByteArray>()
    private var silenceDurationMs = 0L
    private var speechDurationMs = 0L
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0
    private val frameDurationMs = 50L

    fun process(audioBuffer: ByteArray): VadResult {
        val energy = AudioUtils.calculateAudioLevel(audioBuffer)
        val isSpeech = energy >= energyThreshold

        when (state) {
            VadState.SILENCE -> {
                if (isSpeech) {
                    consecutiveSpeechFrames++
                    if (consecutiveSpeechFrames >= 2) {
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
                        val segment = if (speechDurationMs >= minSpeechDurationMs) {
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
                }
                return VadResult(hasSpeech = true)
            }
        }
    }

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

    fun reset() {
        state = VadState.SILENCE
        buffer.clear()
        silenceDurationMs = 0
        speechDurationMs = 0
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
    }

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