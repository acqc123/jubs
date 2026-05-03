package com.jsub.app.util

import kotlin.math.sqrt

/**
 * 音频工具类
 *
 * 提供PCM音频数据的常用处理工具。
 */
object AudioUtils {

    /**
     * 计算音频缓冲区的音量级别（RMS均方根）
     *
     * @param buffer PCM 16bit音频数据
     * @return RMS音量级别（0.0 ~ 32768.0）
     */
    fun calculateAudioLevel(buffer: ByteArray): Double {
        if (buffer.size < 2) return 0.0

        var sum = 0.0
        var count = 0

        // 16bit PCM: 每2个字节一个采样
        for (i in 0 until buffer.size - 1 step 2) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            sum += sample.toDouble() * sample.toDouble()
            count++
        }

        return if (count > 0) sqrt(sum / count) else 0.0
    }

    /**
     * 判断音频是否为静音
     *
     * @param buffer PCM音频数据
     * @param threshold 静音阈值（默认500.0）
     * @return true表示静音
     */
    fun isSilent(buffer: ByteArray, threshold: Double = 500.0): Boolean {
        return calculateAudioLevel(buffer) < threshold
    }

    /**
     * PCM音频重采样
     *
     * 使用简单的线性插值进行重采样。
     *
     * @param input 输入PCM数据
     * @param fromRate 原始采样率
     * @param toRate 目标采样率
     * @param channels 声道数
     * @return 重采样后的PCM数据
     */
    fun resamplePcm(
        input: ByteArray,
        fromRate: Int,
        toRate: Int,
        channels: Int = 1
    ): ByteArray {
        if (fromRate == toRate) return input

        val ratio = toRate.toDouble() / fromRate
        val inputSamples = input.size / (2 * channels)
        val outputSamples = (inputSamples * ratio).toInt()
        val output = ByteArray(outputSamples * 2 * channels)

        for (i in 0 until outputSamples) {
            val srcIndex = i / ratio
            val srcIndex0 = srcIndex.toInt().coerceIn(0, inputSamples - 1)
            val srcIndex1 = (srcIndex0 + 1).coerceAtMost(inputSamples - 1)
            val frac = srcIndex - srcIndex0

            for (ch in 0 until channels) {
                val offset = ch * 2
                val sample0 = ((input[srcIndex0 * 2 * channels + offset + 1].toInt() shl 8) or
                    (input[srcIndex0 * 2 * channels + offset].toInt() and 0xFF)).toShort()
                val sample1 = ((input[srcIndex1 * 2 * channels + offset + 1].toInt() shl 8) or
                    (input[srcIndex1 * 2 * channels + offset].toInt() and 0xFF)).toShort()

                val interpolated = (sample0 * (1 - frac) + sample1 * frac).toInt().toShort()
                val outPos = i * 2 * channels + offset
                output[outPos] = (interpolated.toInt() and 0xFF).toByte()
                output[outPos + 1] = ((interpolated.toInt() shr 8) and 0xFF).toByte()
            }
        }

        return output
    }

    /**
     * 计算PCM音频的时长（毫秒）
     *
     * @param dataSize PCM数据字节数
     * @param sampleRate 采样率
     * @param channels 声道数
     * @param bitsPerSample 位深度
     * @return 时长（毫秒）
     */
    fun calculateDurationMs(
        dataSize: Int,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): Long {
        val bytesPerSample = bitsPerSample / 8
        return (dataSize * 1000L) / (sampleRate * channels * bytesPerSample)
    }
}
