package com.examhelper.shua.data.parse

import com.examhelper.shua.data.model.QuestionOption

/**
 * 选项规范化：修复 OCR/LLM 解析产生的字母重复、空选项、字母跳号。
 * - 同字母选项合并（OCR 常把一个选项拆成两行重复识别）
 * - 空选项剔除
 * - 字母重排为连续 A、B、C…，并同步映射答案字母
 */
object OptionNormalizer {

    fun normalize(options: List<QuestionOption>, answer: String?): Pair<List<QuestionOption>, String?> {
        val cleaned = options.mapNotNull { o ->
            val key = o.key.trim().uppercase()
            val text = o.text.trim()
            if (key.isEmpty() || text.isEmpty()) null else key to text
        }
        if (cleaned.isEmpty()) return emptyList<QuestionOption>() to answer

        // 1) 同字母合并：完全重复丢弃，包含关系取长者，否则视为拆行续接拼接
        val merged = LinkedHashMap<String, String>()
        for ((key, text) in cleaned) {
            val prev = merged[key]
            merged[key] = when {
                prev == null -> text
                prev == text -> prev
                prev.contains(text) || text.contains(prev) ->
                    if (prev.length >= text.length) prev else text
                else -> prev + text
            }
        }

        var opts = merged.entries.map { QuestionOption(it.key, it.value) }
        var ans = answer?.trim()?.uppercase()?.takeIf { it.isNotBlank() }

        // 2) 字母不连续/跳号 → 重排并映射答案
        val canonical = ('A'..'Z').take(opts.size).map { it.toString() }
        val currentKeys = opts.map { it.key }
        if (currentKeys != canonical) {
            val oldToNew = currentKeys.mapIndexed { i, k -> k to canonical[i] }.toMap()
            opts = opts.mapIndexed { i, o -> o.copy(key = canonical[i]) }
            if (ans != null && ans.length <= 5 && ans.all { it in 'A'..'E' }) {
                val mapped = ans.map { oldToNew[it.toString()] }
                // 答案字母都在旧字母表中才重映射，否则保留原值
                if (mapped.all { it != null }) ans = mapped.joinToString("")
            }
        }
        return opts to ans
    }
}
