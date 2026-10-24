package com.examhelper.shua.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 题型 */
enum class QuestionType {
    SINGLE,       // 单选
    MULTIPLE,     // 多选
    JUDGE,        // 判断
    FILL,         // 填空
    SHORT_ANSWER  // 简答
}

/** 学习模式 */
enum class StudyMode {
    SEQUENTIAL,  // 标准做题
    SPEED,       // 速刷
    RECITE,      // 背题
    RANDOM       // 随机巩固
}

/** 题库 */
@Entity(tableName = "banks")
data class QuestionBank(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceFileName: String? = null,
    val questionCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 题目。options/答案分歧等复杂字段以 JSON 字符串存储，由仓库层编解码。 */
@Entity(
    tableName = "questions",
    indices = [Index("bankId"), Index(value = ["bankId", "orderInBank"])]
)
data class Question(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankId: Long,
    val orderInBank: Int,
    val chapter: String? = null,
    val type: QuestionType,
    val stem: String,
    /** JSON: [{"key":"A","text":"..."}, ...] */
    val optionsJson: String? = null,
    /** 单选"B" 多选"ABD" 判断"对"/"错" 其他为参考答案文本 */
    val answer: String? = null,
    val analysis: String? = null,
    /** 答案解析是否由 AI 生成 */
    val aiGenerated: Boolean = false,
    /** AI 置信度 0-100 */
    val confidence: Int? = null,
    /** 三省六部一致率描述，如 "3/4 部一致" */
    val agreement: String? = null,
    /** 各部分歧 JSON: [{"provider":"名","answer":"C","analysis":"..."}] */
    val dissentJson: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** 各模式学习进度（随机巩固的乱序队列持久化在此） */
@Entity(tableName = "study_progress", primaryKeys = ["bankId", "mode"])
data class StudyProgress(
    val bankId: Long,
    val mode: StudyMode,
    /** 顺序/速刷/背题模式当前题下标 */
    val currentIndex: Int = 0,
    /** 随机巩固：乱序后的题目 id 队列 JSON */
    val randomOrderJson: String? = null,
    /** 随机巩固：已抽到第几个 */
    val randomCursor: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

/** 作答记录（判分、错题本、统计都基于它） */
@Entity(
    tableName = "attempt_records",
    indices = [Index("bankId"), Index("questionId"), Index("answeredAt")]
)
data class AttemptRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankId: Long,
    val questionId: Long,
    val mode: StudyMode,
    val userAnswer: String,
    val correct: Boolean,
    /** 填空/简答为用户自评 */
    val selfRated: Boolean = false,
    val answeredAt: Long = System.currentTimeMillis()
)

/** 错题本 */
@Entity(tableName = "wrong_records", primaryKeys = ["questionId"])
data class WrongRecord(
    val questionId: Long,
    val bankId: Long,
    val wrongCount: Int = 1,
    val lastWrongAt: Long = System.currentTimeMillis(),
    /** 之后答对可标记为已掌握 */
    val mastered: Boolean = false
)

/** 收藏 */
@Entity(tableName = "favorites", primaryKeys = ["questionId"])
data class FavoriteRecord(
    val questionId: Long,
    val bankId: Long,
    val createdAt: Long = System.currentTimeMillis()
)

/** 大模型提供方（三省六部中的"部"） */
@Entity(tableName = "llm_providers")
data class LlmProvider(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** OpenAI 兼容 Base URL，如 https://api.deepseek.com/v1 */
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val enabled: Boolean = true,
    /** 主部（三省六部的高权重参考） */
    val isPrimary: Boolean = false,
    val temperature: Float = 0.2f,
    val createdAt: Long = System.currentTimeMillis()
)
