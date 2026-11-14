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
    SHORT_ANSWER, // 简答
    TERM          // 名词解释（与简答并列的主观题型，作答与判分方式相同、标签不同）
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
@Entity(tableName = "banks", indices = [Index("groupId")])
data class QuestionBank(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceFileName: String? = null,
    val questionCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 置顶：首页列表里始终排在最前（长按菜单） */
    val pinned: Boolean = false,
    /** 归档：默认收进「显示已归档题库」里，不占主列表（长按菜单） */
    val archived: Boolean = false,
    /** 题库组 id；null = 普通题库。题库组本身也是 banks 里的一条记录 */
    val groupId: Long? = null,
    /** 该题库是否是一个题库组（组内题目由子题库聚合，不直接存 questions） */
    val isGroup: Boolean = false,
    /** 题库卡片左侧渐变色（AARRGGBB）；null 用默认赤陶色 */
    val accentColor: Long? = null,
    /** 自定义备注 */
    val note: String? = null,
    /** 首页拖拽排序键，越小越靠前 */
    val sortOrder: Int = 0,
    /** 合并撤销：记录合并前的子题库 id（JSON 数组）；null = 不是合并题库 */
    val mergedFromJson: String? = null,
    /** 合并撤销用的完整快照（原题库元数据 + 每题原 bankId/order） */
    val mergeSnapshotJson: String? = null
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
    /** 原文页码（PDF 导入记录，章节划分辅助） */
    val sourcePage: Int? = null,
    /** JSON: [{"key":"A","text":"..."}, ...] */
    val optionsJson: String? = null,
    /** 单选"B" 多选"ABD" 判断"对"/"错" 其他为参考答案文本 */
    val answer: String? = null,
    /** AI 重新生成与原题库答案冲突时，保留的题库原答案（答案页双展示） */
    val originalAnswer: String? = null,
    /** AI 给出的答案（无论是否与题库一致；answer 为题库权威答案优先） */
    val aiAnswer: String? = null,
    /** AI 答案与题库参考答案一致（做对时页面标「与参考答案一致」） */
    val answerVerified: Boolean = false,
    /** 该题解析是否经「深度解题」升级过 */
    val deepSolved: Boolean = false,
    /** 该题解析是否由「极简解析」生成 */
    val minimalSolved: Boolean = false,
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
    val mastered: Boolean = false,
    /** 重点标记：错题本里可单独成组复刷（不依赖是否还在未掌握列表里） */
    val flagged: Boolean = false
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

/** 后台 AI 生成任务（WorkManager 驱动，断点续传按"已答即跳过"） */
@Entity(tableName = "generate_jobs")
data class GenerateJob(
    @PrimaryKey val bankId: Long,
    /** RUNNING / DONE / FAILED / CANCELED */
    val stage: String = "RUNNING",
    val forceAll: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val ok: Int = 0,
    val fail: Int = 0,
    val message: String? = null,
    /** 累计 token 用量（缓存命中/未命中/输出），费用透明化 */
    val cacheHitTokens: Long = 0,
    val cacheMissTokens: Long = 0,
    val outputTokens: Long = 0,
    /**
     * **已废弃，不再写入**：DeepSeek 计费有几分钟延迟，任务前后两次读余额几乎总相等，
     * 差值恒为 0，据此显示的「实扣」纯属误导。列保留只为兼容旧库；
     * 实际消费改由 `balance_snapshots` 的多天累计得出（见 UsageStats）。
     */
    val balanceBefore: Double? = null,
    val balanceAfter: Double? = null,
    val balanceCurrency: String? = null,
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
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
    /**
     * 累计 token 用量。AI 解析本身也在花钱 —— 只统计生成那一段，
     * 用户看到的「成本」就只覆盖了一半开销，这也是「和实际对不上」的来源之一。
     */
    val cacheHitTokens: Long = 0,
    val cacheMissTokens: Long = 0,
    val outputTokens: Long = 0,
    /**
     * **已废弃，不再写入**（原因同 GenerateJob.balanceBefore）：计费延迟导致差值恒为 0。
     */
    val balanceBefore: Double? = null,
    val balanceAfter: Double? = null,
    val balanceCurrency: String? = null,
    /**
     * AI 解析时模型的实时输出尾巴（只保留最近一小段，用于导入页的「生成过程预览」）。
     * 不落完整输出：一次导入可能几十万字符，全存进数据库既占空间也没人看。
     */
    val streamTail: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 账户余额快照（DeepSeek 官方 `/user/balance`）。
 *
 * 为什么要单独存一张表：DeepSeek **没有**用量/账单查询接口（`/user/usage`、
 * `/user/bills`、`/billing` 等全部 404，只有 `/user/balance`），所以「近 7/30 天
 * 消费」只能用余额变化反推。
 *
 * 算法：窗口内按时间排序，把相邻两次快照的**下降量**累加；余额上升视为充值，整段跳过。
 * 结算延迟只有几分钟，所以 7/30 天这种长窗口的累计结果和官方账单是对得上的。
 *
 * 采样时机：应用打开（每天最多一次）+ 每个任务开始/结束（30 分钟内不重复采样）。
 * 采样越密，被「充值」掩盖掉的消费窗口越小。
 */
@Entity(
    tableName = "balance_snapshots",
    indices = [Index(value = ["providerId", "sampledAt"])]
)
data class BalanceSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    /** 总可用余额（含赠送） */
    val amount: Double,
    val currency: String = "CNY",
    val sampledAt: Long = System.currentTimeMillis()
)

/** 每次 AI 请求的本地账本；用于跨导入/生成/问 AI/深度解题统一估算。 */
@Entity(
    tableName = "ai_usage_records",
    indices = [Index(value = ["providerId", "createdAt"]), Index("taskId")]
)
data class AiUsageRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 单次调用的唯一编号 */
    val callId: String,
    /** 一次用户任务（导入/生成/单次追问）的编号 */
    val taskId: String,
    val providerId: Long,
    val providerName: String,
    val model: String,
    /** IMPORT_PARSE / ANSWER_GENERATE / ASK_AI / DEEP_SOLVE / MINIMAL_SOLVE / CONNECTION_TEST / OTHER */
    val category: String,
    val cacheHitTokens: Long = 0,
    val cacheMissTokens: Long = 0,
    val outputTokens: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)
