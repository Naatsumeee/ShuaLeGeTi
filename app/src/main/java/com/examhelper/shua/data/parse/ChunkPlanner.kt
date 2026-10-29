package com.examhelper.shua.data.parse

/**
 * 按「题目边界」切分 OCR 文本。
 *
 * 旧实现只按行累加到 size 就切，会让一道题的题干落在上一段、选项落在下一段，
 * 结果是两道残缺的「题」——即用户反馈的「切在题目中间导致缺题/不完整」。
 *
 * 这里改成：先吃到超出 size，再向后找到**最近的题目/章节起始行**才下刀。
 * 于是切点永远落在一道题开始之前，每道题完整地属于且仅属于一个分块，
 * 既不会截断题目，也不需要重叠文本再去重。
 * 唯一的例外是极端情况——单段连续内容超过 size*3 都没有任何边界，
 * 此时只能硬切（否则永远切不开）。
 */
object ChunkPlanner {

    data class Chunk(
        val index: Int,
        val text: String,
        val startLine: Int,
        val endLine: Int
    )

    /** `1.` `2、` `（3）` `4)` 这类题号开头 */
    private val questionStartRe =
        Regex("""^\s*[（(]?\s*(\d{1,4})\s*[）)]?\s*[、.．,，:：)）]\s*\S""")

    /** 章节 / 大题 / Markdown 标题开头 */
    private val sectionStartRe = Regex(
        """^\s*(第\s*[一二三四五六七八九十百零\d]+\s*[章节部分卷篇]""" +
            """|#{1,6}\s+\S""" +
            """|[（(]\s*[一二三四五六七八九十]{1,3}\s*[）)]\s*\S""" +
            """|[一二三四五六七八九十]{1,3}\s*[、.．]\s*\S)"""
    )

    fun isQuestionStart(line: String): Boolean = questionStartRe.containsMatchIn(line)

    fun isSectionStart(line: String): Boolean = sectionStartRe.containsMatchIn(line)

    /** 边界行：可以在它**之前**下刀 */
    fun isBoundary(line: String): Boolean = isQuestionStart(line) || isSectionStart(line)

    /**
     * @param size 目标分块字符数（软上限，允许为保住题目完整性而超出）
     */
    fun plan(text: String, size: Int): List<Chunk> {
        if (text.isBlank()) return emptyList()
        if (text.length <= size) return listOf(Chunk(0, text, 0, 0))

        val lines = text.lines()
        val n = lines.size
        val bound = BooleanArray(n) { isBoundary(lines[it]) }

        val chunks = mutableListOf<Chunk>()
        var start = 0
        var index = 0
        // 完全没有边界时的硬上限：避免一段超长正文把整篇挤进一个分块
        val hardCap = size * 3

        while (start < n) {
            var end = start
            var len = 0
            while (end < n) {
                len += lines[end].length + 1
                end++
                if (len >= size) break
            }

            // 单行本身就超长（OCR 没换行的整段文字）：只能按字符硬切
            if (end == start + 1 && lines[start].length > hardCap) {
                val line = lines[start]
                var from = 0
                while (from < line.length) {
                    val to = minOf(from + size, line.length)
                    chunks += Chunk(index++, line.substring(from, to), start, start)
                    from = to
                }
                start++
                continue
            }

            if (end < n) {
                // 从 end 起向后找最近的边界行；超过 hardCap 则放弃，接受硬切
                var probe = end
                var extra = 0
                var found = -1
                while (probe < n && extra < hardCap) {
                    if (bound[probe]) {
                        found = probe
                        break
                    }
                    extra += lines[probe].length + 1
                    probe++
                }
                if (found > start) end = found
            }

            if (end <= start) end = start + 1 // 兜底，防死循环
            val body = lines.subList(start, end).joinToString("\n")
            if (body.isNotBlank()) {
                chunks += Chunk(index++, body, start, end - 1)
            }
            start = end
        }
        return chunks
    }

    /** 去重键：去掉空白与标点后的小写题干（跨块重复的题只保留第一次出现的） */
    fun dedupeKey(stem: String): String =
        stem.filter { it.isLetterOrDigit() }.lowercase()
}
