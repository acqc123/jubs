package com.jsub.app.speech.model

import kotlin.math.*

/**
 * Mel频谱特征提取器
 *
 * 将PCM音频（16kHz, 16bit）转换为Mel Spectrogram特征，
 * 作为SenseVoice ONNX模型的输入。
 */
class MelFeatureExtractor(
    private val sampleRate: Int = 16000,
    private val nMels: Int = 80,        // Mel频带数
    private val nFFT: Int = 400,        // FFT点数（25ms @ 16kHz）
    private val hopLength: Int = 160,   // 帧移（10ms @ 16kHz）
    private val fMin: Float = 20f,      // 最低频率
    private val fMax: Float = 8000f     // 最高频率
) {
    private val melFilterBank: Array<FloatArray> by lazy { createMelFilterBank() }
    private val hammingWindow: FloatArray by lazy { createHammingWindow(nFFT) }

    /**
     * 提取Mel特征
     *
     * @param pcmData 16bit PCM音频数据
     * @return [nFrames x nMels] 的Mel Spectrogram
     */
    fun extract(pcmData: ByteArray): Array<FloatArray> {
        // 1. ByteArray → ShortArray → FloatArray（归一化到[-1, 1]）
        val samples = pcmDataToFloats(pcmData)

        // 2. 预加重
        val preemphasized = preEmphasis(samples)

        // 3. 分帧 + 加窗 + FFT + Mel滤波
        val nFrames = (preemphasized.size - nFFT) / hopLength + 1
        val melSpecs = Array(nFrames.coerceAtLeast(1)) { frameIdx ->
            val frameStart = frameIdx * hopLength
            val frame = FloatArray(nFFT) { i ->
                if (frameStart + i < preemphasized.size) {
                    preemphasized[frameStart + i] * hammingWindow[i]
                } else 0f
            }

            // FFT → 功率谱（简化：只取实部平方）
            val powerSpectrum = computePowerSpectrum(frame)

            // Mel滤波器组
            FloatArray(nMels) { melIdx ->
                var sum = 0f
                for (fftIdx in 0 until nFFT / 2 + 1) {
                    sum += powerSpectrum[fftIdx] * melFilterBank[melIdx][fftIdx]
                }
                // 对数压缩
                ln(max(sum, 1e-10f))
            }
        }

        return melSpecs
    }

    /** PCM ByteArray → FloatArray（归一化） */
    private fun pcmDataToFloats(pcmData: ByteArray): FloatArray {
        val nSamples = pcmData.size / 2
        return FloatArray(nSamples) { i ->
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            (sample.toShort().toFloat() / 32768.0f)
        }
    }

    /** 预加重滤波器 */
    private fun preEmphasis(samples: FloatArray, coeff: Float = 0.97f): FloatArray {
        return FloatArray(samples.size) { i ->
            if (i == 0) samples[0]
            else samples[i] - coeff * samples[i - 1]
        }
    }

    /** 汉明窗 */
    private fun createHammingWindow(size: Int): FloatArray {
        return FloatArray(size) { i ->
            0.54f - 0.46f * cos(2 * PI * i / (size - 1)).toFloat()
        }
    }

    /** 计算功率谱（简化FFT） */
    private fun computePowerSpectrum(frame: FloatArray): FloatArray {
        val n = frame.size
        val result = FloatArray(n / 2 + 1)
        // DFT简化实现
        for (k in 0..n / 2) {
            var real = 0f
            var imag = 0f
            for (t in 0 until n) {
                val angle = -2 * PI * k * t / n
                real += frame[t] * cos(angle).toFloat()
                imag += frame[t] * sin(angle).toFloat()
            }
            result[k] = (real * real + imag * imag) / n
        }
        return result
    }

    /** 创建Mel滤波器组 */
    private fun createMelFilterBank(): Array<FloatArray> {
        val nFftBins = nFFT / 2 + 1
        val melPoints = FloatArray(nMels + 2)
        val fMinMel = hzToMel(fMin)
        val fMaxMel = hzToMel(fMax)

        for (i in 0 until nMels + 2) {
            melPoints[i] = melToHz(fMinMel + (fMaxMel - fMinMel) * i / (nMels + 1))
        }

        return Array(nMels) { melIdx ->
            FloatArray(nFftBins) { fftIdx ->
                val freq = fftIdx * sampleRate.toFloat() / nFFT
                when {
                    freq < melPoints[melIdx] || freq > melPoints[melIdx + 2] -> 0f
                    freq <= melPoints[melIdx + 1] ->
                        (freq - melPoints[melIdx]) / (melPoints[melIdx + 1] - melPoints[melIdx])
                    else ->
                        (melPoints[melIdx + 2] - freq) / (melPoints[melIdx + 2] - melPoints[melIdx + 1])
                }
            }
        }
    }

    private fun hzToMel(hz: Float): Float = 2595f * log10(1 + hz / 700f)
    private fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)
}
