package com.examhelper.shua.data.repo

import com.examhelper.shua.data.model.JsonCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 合并前单个题库的元数据快照。 */
@Serializable
internal data class MergeBankSnapshot(
    val id: Long,
    val name: String,
    val sourceFileName: String? = null,
    val questionCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val groupId: Long? = null,
    val isGroup: Boolean = false,
    val accentColor: Long? = null,
    val note: String? = null,
    val sortOrder: Int = 0
)

/** 合并前单道题的归属与顺序。题 id 在合并时保持不变。 */
@Serializable
internal data class MergeQuestionSnapshot(
    val bankId: Long,
    val questionId: Long,
    val order: Int
)

@Serializable
internal data class MergeSnapshot(
    val banks: List<MergeBankSnapshot>,
    val questions: List<MergeQuestionSnapshot>
) {
    /** 撤销合并时恢复 attempt/wrong/favorite/kill 的唯一可靠依据。 */
    val questionOwners: Map<Long, Long>
        get() = questions.associate { it.questionId to it.bankId }
}

internal object MergeSnapshotCodec {
    fun encode(snapshot: MergeSnapshot): String = JsonCodec.json.encodeToString(snapshot)

    /**
     * 旧版快照缺少后加字段时由默认值兼容；但重复 id、题目指向不存在的题库、
     * 同一道题出现多条归属记录都属于损坏数据，宁可拒绝撤销也不能恢复错。
     */
    fun decode(raw: String?): MergeSnapshot? {
        if (raw.isNullOrBlank()) return null
        val snapshot = runCatching {
            JsonCodec.json.decodeFromString<MergeSnapshot>(raw)
        }.getOrNull() ?: return null
        if (snapshot.banks.isEmpty()) return null
        val bankIds = snapshot.banks.map { it.id }
        if (bankIds.size != bankIds.distinct().size) return null
        val questionIds = snapshot.questions.map { it.questionId }
        if (questionIds.size != questionIds.distinct().size) return null
        val owners = bankIds.toSet()
        if (snapshot.questions.any { it.bankId !in owners }) return null
        return snapshot
    }
}
