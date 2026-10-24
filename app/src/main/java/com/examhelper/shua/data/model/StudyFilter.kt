package com.examhelper.shua.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 刷题前的多维筛选条件（也作为进度重置的指纹） */
@Serializable
data class StudyFilter(
    /** 选中的章节（空 = 全部） */
    val chapters: List<String> = emptyList(),
    /** 选中的题型 QuestionType.name（空 = 全部） */
    val types: List<String> = emptyList(),
    /** 选中的医学子类型 A1/A2/A3/B（空 = 全部） */
    val subtypes: List<String> = emptyList(),
    /** ALL / UNDONE 未做 / WRONG 错题 / FAVORITE 收藏 / UNKILLED 未斩 / KILLED 已斩 */
    val status: String = "ALL"
) {
    val isDefault: Boolean
        get() = chapters.isEmpty() && types.isEmpty() && subtypes.isEmpty() && status == "ALL"

    fun fingerprint(): String = JsonCodec.json.encodeToString(this)

    companion object {
        fun fromFingerprint(raw: String?): StudyFilter? =
            raw?.let { runCatching { JsonCodec.json.decodeFromString<StudyFilter>(it) }.getOrNull() }

        val STATUS_OPTIONS = listOf("ALL" to "全部", "UNDONE" to "未做", "WRONG" to "错题", "FAVORITE" to "收藏", "UNKILLED" to "未斩", "KILLED" to "已斩")
    }
}
