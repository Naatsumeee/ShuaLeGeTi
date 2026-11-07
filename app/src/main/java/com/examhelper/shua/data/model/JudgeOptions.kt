package com.examhelper.shua.data.model

import com.examhelper.shua.data.db.QuestionType

/**
 * 判断题的选项与答案归一化。
 *
 * 真实题库里判断题几乎从不写「A.正确 / B.错误」—— 只有一行题干加一句
 * 「答案：对」。旧逻辑把它归成 JUDGE 却不补选项，做题页因此渲染不出任何
 * 可点的行，用户看到的就是「这道题没法作答」。
 *
 * 这里的约定：
 * - 判断题永远是两个固定选项：A=正确、B=错误（显示层再按需本地化成「对/错」）
 * - 答案一律归一成字母 A / B，与选项字母一一对应
 */
object JudgeOptions {

    const val KEY_TRUE = "A"
    const val KEY_FALSE = "B"

    val DEFAULT: List<QuestionOption> = listOf(
        QuestionOption(KEY_TRUE, "正确"),
        QuestionOption(KEY_FALSE, "错误")
    )

    private val TRUE_WORDS = setOf(
        "对", "正确", "是", "√", "✓", "✔", "T", "Y", "TRUE", "YES", "A", "1"
    )
    private val FALSE_WORDS = setOf(
        "错", "错误", "否", "×", "✗", "✘", "F", "N", "FALSE", "NO", "B", "0"
    )

    /** 该答案文本是否表示「正确」/「错误」；两者都不是返回 null */
    fun truthValue(raw: String?): Boolean? {
        val t = raw?.trim()?.uppercase()?.replace(" ", "") ?: return null
        if (t.isEmpty()) return null
        return when {
            t in TRUE_WORDS -> true
            t in FALSE_WORDS -> false
            // 「答案：对的」「正确（√）」这类带修饰的写法，按前缀再兜一层
            t.startsWith("对") || t.startsWith("正确") || t.startsWith("√") -> true
            t.startsWith("错") || t.startsWith("错误") || t.startsWith("×") -> false
            else -> null
        }
    }

    /** 判断题答案归一成 A / B；无法判断时返回 null（宁可留空也不瞎填） */
    fun letterFor(raw: String?): String? = when (truthValue(raw)) {
        true -> KEY_TRUE
        false -> KEY_FALSE
        null -> null
    }

    /**
     * 保证判断题一定有可点的两个选项，并把答案对齐到字母。
     *
     * @param options 数据库/解析结果里的现有选项
     * @param answer  现有答案（可能是「对」「√」「A」或 null）
     * @return 渲染/落库应使用的选项与答案
     */
    fun normalize(
        type: QuestionType?,
        options: List<QuestionOption>,
        answer: String?
    ): Pair<List<QuestionOption>, String?> {
        if (type != QuestionType.JUDGE) return options to answer

        // 已经有两个像样的选项（内容里出现了对/错字样）就沿用，只统一答案字母
        val usable = options.filter { it.key.isNotBlank() && it.text.isNotBlank() }
        if (usable.size >= 2 && usable.all { truthValue(it.text) != null }) {
            val letter = letterFor(answer)
                ?: usable.firstOrNull { truthValue(it.text) == truthValue(answer) }?.key
            return usable to (letter ?: answer)
        }

        // 只有一个「对/错」选项时按内容定位，避免把「错误」错配成 A
        if (usable.size == 1) {
            val only = truthValue(usable[0].text)
            val letter = when (only) {
                true -> KEY_TRUE
                false -> KEY_FALSE
                null -> null
            }
            return DEFAULT to (letter ?: letterFor(answer) ?: answer)
        }

        return DEFAULT to (letterFor(answer) ?: answer)
    }

    /** 显示用：把 A/B 换成「正确/错误」，避免小屏上选项看起来像选择题 */
    fun displayFor(type: QuestionType?, options: List<QuestionOption>): List<QuestionOption> =
        if (type != QuestionType.JUDGE) options
        else options.map { opt ->
            when (truthValue(opt.text)) {
                true -> opt.copy(text = if (opt.text.trim() in setOf("√", "✓", "✔", "T", "Y")) "正确" else opt.text.trim().ifBlank { "正确" })
                false -> opt.copy(text = if (opt.text.trim() in setOf("×", "✗", "✘", "F", "N")) "错误" else opt.text.trim().ifBlank { "错误" })
                null -> opt
            }
        }

    /**
     * 显示用的答案文案：判断题把 A/B（以及 √/对/T）统一成「正确 / 错误」，
     * 其他题型原样返回。单题查看页、测试报告、错题本都用它，避免同一个答案
     * 在不同页面显示成「A」和「对」两种样子。
     */
    fun answerText(type: QuestionType?, answer: String?): String {
        val raw = answer?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        if (type != QuestionType.JUDGE) return raw
        return when (truthValue(raw)) {
            true -> "正确"
            false -> "错误"
            null -> raw
        }
    }
}
