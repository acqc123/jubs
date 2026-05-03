package com.jsub.app.api

/**
 * 翻译API接口
 *
 * 将日文文本翻译为简体中文
 */
interface TranslationApi {
    /**
     * 翻译日文文本为中文
     *
     * @param japaneseText 日文原文
     * @return 简体中文翻译结果
     */
    suspend fun translate(japaneseText: String): String
}
