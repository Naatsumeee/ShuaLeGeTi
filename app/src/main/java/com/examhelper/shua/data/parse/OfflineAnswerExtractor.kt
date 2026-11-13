package com.examhelper.shua.data.parse

/**
 * 离线题库里「随题标注答案」的抽取器。
 *
 * 真实题库的答案写法远比 `答案：A` 多：`【参考答案】A`、`正确答案是 A`、
 * `答案 A`、`参考答案及解析：A`、`D. xxx 参考答案：A`，甚至把答案和解析
 * 塞在同一行。旧逻辑只认整行答案；遇到最后一种时，`D. xxx 参考答案：A`
 * 会先被当作选项 D 的正文，答案就被吞进选项里。
 *
 * 这里不绑定「答案必须占一整行」：先找答案标签，再从标签后解析答案值，
 * 返回标签前、标签后的文本，让调用方决定把前半段当题干/选项、后半段当解析。
 */
internal object OfflineAnswerExtractor {

    data class Hit(
        val answer: String,
        /** 答案标签之前的文本（题干或选项正文） */
        val before: String,
        /** 答案值之后剩下的文本（通常是解析） */
        val after: String,
        /** 若答案后紧跟解析标签，已抽出的解析正文 */
        val analysis: String?
    )

    internal data class Value(
        val answer: String,
        val consumed: Int
    )

    /**
     * 标签本身。顺序很重要：先匹配更长的「参考答案及解析」，再匹配「参考答案」，
     * 最后才是「答案」。`答案解析` 不会被当成答案标签。
     */
    private val markerRegex = Regex(
        """(?i)(?:[\[【(（]\s*)?(?:(?:参考|正确|标准|官方|本题)\s*答案|答案)\s*(?:及|与|和)?\s*(?:解析|详解)?(?:\s*[\]】)）])?""",
        RegexOption.IGNORE_CASE
    )
    private val englishMarkerRegex = Regex("""(?i)\banswer\b""")
    private val valueSeparator = Regex("""^\s*(?:[:：=]|is\b|为|是|选)?\s*""", RegexOption.IGNORE_CASE)
    private val letterValue = Regex(
        """^(?:(?:应|请)?选(?:择|项)?\s*[:：]?\s*|选项\s*[:：]?\s*)?[\[【(（]?\s*([A-H](?:\s*[,，、/和及]\s*[A-H])*|[A-H]{2,8})(?![A-Za-z])""",
        RegexOption.IGNORE_CASE
    )
    private val judgeValue = Regex(
        """^(对|错|正确|错误|√|✔|✓|×|✗|TRUE|FALSE|T|F|是|否)(?![A-Za-z\u4e00-\u9fa5])""",
        RegexOption.IGNORE_CASE
    )
    private val analysisMarker = Regex(
        """(?i)[\s。；;，,、]*(?:[\[【(（]\s*)?(?:答案解析|试题解析|解析|详解|分析|考点|说明)\s*(?:[\]】)）])?\s*[:：]?\s*"""
    )

    /** 判断一个标签后的值是不是「解析」正文而不是答案字母/判断词。 */
    private fun looksLikeAnswerValue(raw: String): Boolean {
        val sep = valueSeparator.find(raw)?.value.orEmpty()
        val rest = raw.substring(sep.length)
        return letterValue.containsMatchIn(rest) || judgeValue.containsMatchIn(rest)
    }

    fun find(text: String): Hit? {
        val marker = markerRegex.findAll(text).firstOrNull() ?: englishMarkerRegex.find(text) ?: return null
        // markerRegex 会在「答案解析」里命中「答案」，但后面是「解析」而不是答案值。
        // 这种行属于解析，不应被当成答案。
        val afterMarker = text.substring(marker.range.last + 1)
        // 标签之后紧跟答案值才算答案；「答案解析：因为乙正确」没有答案值，
        // 必须继续找下一处真正的「答案：B」。
        if (!looksLikeAnswerValue(afterMarker)) {
            val rest = text.substring(marker.range.last + 1)
            val next = markerRegex.findAll(rest).firstOrNull { looksLikeAnswerValue(rest.substring(it.range.last + 1)) }
                ?: return null
            val hit = parseValue(rest.substring(next.range.last + 1)) ?: return null
            return Hit(
                answer = hit.answer,
                before = text.substring(0, marker.range.first).trimEnd(' ', '\t', '（', '(', '[', '【'),
                after = rest.substring((next.range.last + 1 + hit.consumed).coerceAtMost(rest.length)).trim(),
                analysis = null
            )
        }
        val value = parseValue(afterMarker) ?: return null
        val valueStart = marker.range.last + 1
        val valueEnd = (valueStart + value.consumed).coerceAtMost(text.length)
        val afterValue = text.substring(valueEnd).trim()
        val analysis = extractAnalysis(afterValue)
        return Hit(
            answer = value.answer,
            before = text.substring(0, marker.range.first).trimEnd(' ', '\t', '（', '(', '[', '【'),
            after = afterValue,
            analysis = analysis
        )
    }

    fun parseValue(raw: String): Value? {
        val sep = valueSeparator.find(raw)?.value.orEmpty()
        val rest = raw.substring(sep.length)
        if (rest.isBlank()) return null
        judgeValue.find(rest)?.let { m ->
            return Value(m.groupValues[1], sep.length + m.value.length)
        }
        letterValue.find(rest)?.let { m ->
            val letters = m.groupValues[1].uppercase().filter { it in 'A'..'H' }
            if (letters.isNotEmpty()) return Value(letters, sep.length + m.value.length)
        }

        // 主观题：答案通常是一整句。解析标签之后的部分单独抽走，避免把
        // 「答案：xxx 解析：yyy」整段都塞进答案字段。
        val tail = rest.substring(0, analysisMarker.find(rest)?.range?.first ?: rest.length)
            .trim()
            // 句号/分号是正文的一部分；只去掉用于分段的逗号。
            .trimEnd('，', ',')
        if (tail.isBlank()) return null
        val consumed = sep.length + (rest.indexOf(tail).takeIf { it >= 0 } ?: 0) + tail.length
        return Value(tail, consumed)
    }

    private fun extractAnalysis(after: String): String? {
        val m = analysisMarker.find(after) ?: return null
        val body = after.substring(m.range.last + 1).trim()
        return body.takeIf { it.isNotBlank() }
    }
}
