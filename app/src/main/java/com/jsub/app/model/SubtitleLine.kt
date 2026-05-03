package com.jsub.app.model

/**
 * 字幕行数据模型
 *
 * 包含日文原文、中文翻译及相关元数据。
 *
 * @param japaneseText 日文原文
 * @param chineseText 简体中文翻译
 * @param timestamp 字幕生成时间戳（毫秒）
 * @param isFinal 是否是最终结果（true=最终, false=中间结果）
 */
data class SubtitleLine(
    val japaneseText: String,
    val chineseText: String,
    val timestamp: Long,
    val isFinal: Boolean
)
