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

/** 医学题型子类型标注 */
object QuestionSubtype {
    const val A1 = "A1"  // 单句型单选
    const val A2 = "A2"  // 病例摘要型单选
    const val A3 = "A3"  // 病例组型（共用题干）
    const val B = "B"    // 配伍题（共用选项）
    val ALL = listOf(A1, A2, A3, B)
}

/** 学习模式 */
enum class StudyMode {
    SEQUENTIAL,  // 标准做题
    SPEED,       // 速刷
    RECITE,      // 背题
    RANDOM,      // 随机巩固
    EXAM         // 测试模式
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

/** 题目。选项/分歧等复杂字段以 JSON 字符串存储，由仓库层编解码。 */
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
    /** 医学题型子类型：A1/A2/A3/B，普通题为 null */
    val subtype: String? = null,
    val stem: String,
    /** A3 共用题干材料（组内每题冗余存储同一材料） */
    val sharedStem: String? = null,
    /** B 型共用选项组 JSON（组内每题冗余存储），格式同 optionsJson */
    val sharedOptionsJson: String? = null,
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
    /** 本次进度对应的筛选条件指纹（变更时自动重置进度） */
    val filterJson: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

/** 作答记录（判分、错题本、统计都基于它） */
@Entity(
    tableName = "attempt_records",
    indices = [Index("bankId"), Index("questionId"), Index("answeredAt"), Index("runId")]
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
    /** 测试模式的一次组卷运行 id（报告按此聚合） */
    val runId: String? = null,
    /** 本题作答用时毫秒 */
    val durationMs: Long? = null,
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

/** 斩题（已掌握，默认不再出现在刷题中） */
@Entity(tableName = "kill_records", primaryKeys = ["questionId"])
data class KillRecord(
    val questionId: Long,
    val bankId: Long,
    val killedAt: Long = System.currentTimeMillis()
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

/** 后台导入/解析任务（WorkManager 驱动，界面销毁不中断） */
@Entity(tableName = "import_jobs")
data class ImportJob(
    @PrimaryKey val id: String,
    val bankName: String,
    val sourceName: String,
    /** INGEST / PARSE / DONE / FAILED / CANCELED */
    val stage: String = "INGEST",
    val totalUnits: Int = 0,
    val doneUnits: Int = 0,
    val message: String? = null,
    /** OCR 全文落盘路径 */
    val rawTextPath: String? = null,
    /** 已解析题目 JSONL 落盘路径（增量追加） */
    val parsedPath: String? = null,
    val parsedCount: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
