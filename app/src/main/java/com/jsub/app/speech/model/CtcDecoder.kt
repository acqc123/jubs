package com.jsub.app.speech.model

import android.util.Log

/**
 * CTC解码器
 *
 * 将SenseVoice ONNX模型的输出（每帧的token概率分布）
 * 解码为最终文本。
 */
class CtcDecoder {

    companion object {
        private const val TAG = "CtcDecoder"
        private const val BLANK_ID = 0
        private const val UNK_ID = 1
        private const val SOS_EOS_ID = 2
    }

    /**
     * 贪心解码
     *
     * @param probs [nFrames x vocabSize] 每帧每个token的对数概率
     * @return 解码后的文本
     */
    fun greedyDecode(probs: Array<FloatArray>): String {
        // 1. 每帧取概率最高的token
        val bestTokens = IntArray(probs.size) { frameIdx ->
            var bestToken = BLANK_ID
            var bestProb = probs[frameIdx][BLANK_ID]
            for (tokenId in 1 until probs[frameIdx].size) {
                if (probs[frameIdx][tokenId] > bestProb) {
                    bestProb = probs[frameIdx][tokenId]
                    bestToken = tokenId
                }
            }
            bestToken
        }

        // 2. 去重去blank
        val result = StringBuilder()
        var prevToken = -1
        for (token in bestTokens) {
            when {
                token == BLANK_ID -> { /* skip blank */ }
                token == UNK_ID -> { /* skip unk */ }
                token == SOS_EOS_ID -> { /* skip sos/eos */ }
                token == prevToken -> { /* skip repeat */ }
                token >= 3 -> {
                    // token id 映射到字符
                    val char = tokenIdToChar(token)
                    if (char != null) {
                        result.append(char)
                    }
                }
            }
            prevToken = token
        }

        return result.toString().trim()
    }

    /**
     * Token ID → 字符映射
     *
     * SenseVoice使用多语言字符集。这里提供一个简化的映射，
     * 覆盖常用的日文字符。
     *
     * 在实际部署中，应该使用完整的词汇表文件（vocab.txt）。
     */
    private fun tokenIdToChar(tokenId: Int): String? {
        // 简化映射：SenseVoice的vocab中，日文假名和汉字
        // 实际运行时从vocab文件加载完整映射
        return when {
            tokenId in 3..10000 -> {
                // 从预加载的vocab映射中获取
                vocabMap[tokenId]
            }
            else -> null
        }
    }

    /**
     * 加载词汇表文件
     */
    fun loadVocabulary(vocabLines: List<String>) {
        vocabMap.clear()
        vocabLines.forEachIndexed { index, line ->
            val token = line.trim()
            if (token.isNotEmpty()) {
                vocabMap[index + 3] = token // token id从3开始
            }
        }
        Log.i(TAG, "Loaded vocabulary: ${vocabMap.size} tokens")
    }

    /** 词汇表映射（token id → 字符） */
    private val vocabMap = mutableMapOf<Int, String>()

    /** 检查词汇表是否已加载 */
    fun isVocabularyLoaded(): Boolean = vocabMap.isNotEmpty()
}
