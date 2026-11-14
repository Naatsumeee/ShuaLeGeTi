package com.examhelper.shua.data.model

/** 错题本导出继承的列表来源。 */
enum class PdfExportSource(val label: String) {
    UNMASTERED("未掌握"),
    MASTERED("已掌握"),
    FLAGGED("重点"),
    FAVORITES("收藏"),
    KILLED("斩题墙")
}

enum class PdfExportScope { BANK, CHAPTERS }

enum class PdfAnswerMode {
    DEEP,
    MINIMAL,
    ANSWER_ONLY
}

enum class PdfAnswerPlacement { AFTER_EACH, AT_END }

data class PdfExportOptions(
    val includeAnswers: Boolean = true,
    val mode: PdfAnswerMode = PdfAnswerMode.DEEP,
    val placement: PdfAnswerPlacement = PdfAnswerPlacement.AFTER_EACH
)

data class PdfExportRequest(
    val bankId: Long,
    val scope: PdfExportScope,
    val chapters: Set<String> = emptySet()
)

/** PDF 层只依赖纯数据，避免 Android/Compose 类型进入排版测试。 */
data class PdfQuestion(
    val id: Long,
    val bankId: Long,
    val chapter: String?,
    val type: String,
    val stem: String,
    val options: List<String>,
    val answer: String?,
    val analysis: String?,
    val deepSolved: Boolean,
    val minimalSolved: Boolean = false
)

data class WrongBookExport(
    val source: PdfExportSource,
    val questions: List<PdfQuestion>
) {
    fun select(request: PdfExportRequest): List<PdfQuestion> = questions.filter { q ->
        q.bankId == request.bankId &&
            (request.scope == PdfExportScope.BANK || q.chapter in request.chapters)
    }

    fun answerLines(options: PdfExportOptions): List<String> = questions.mapIndexed { i, q ->
        buildString {
            append(i + 1).append(".【答案】").append(q.answer?.takeIf { it.isNotBlank() } ?: "无")
            if (options.mode != PdfAnswerMode.ANSWER_ONLY) {
                val analysis = analysisFor(q, options.mode)
                append('\n').append("【解析】").append(analysis?.let(::plainText) ?: "无")
            }
        }
    }

    fun questionBlocks(options: PdfExportOptions): List<String> = questions.mapIndexed { i, q ->
        buildString {
            append(i + 1).append(". ").append(plainText(q.stem))
            q.options.forEach { append('\n').append(plainText(it)) }
            if (options.includeAnswers && options.placement == PdfAnswerPlacement.AFTER_EACH) {
                append('\n').append("【答案】").append(q.answer?.takeIf { it.isNotBlank() } ?: "无")
                if (options.mode != PdfAnswerMode.ANSWER_ONLY) {
                    append('\n').append("【解析】")
                        .append(analysisFor(q, options.mode)?.let(::plainText) ?: "无")
                }
            }
        }
    }

    fun missingAnalysisIds(mode: PdfAnswerMode): List<Long> = when (mode) {
        PdfAnswerMode.ANSWER_ONLY -> emptyList()
        PdfAnswerMode.DEEP -> questions.filter { !it.deepSolved || it.analysis.isNullOrBlank() }.map { it.id }
        PdfAnswerMode.MINIMAL -> questions.filter { !it.minimalSolved || it.analysis.isNullOrBlank() }.map { it.id }
    }

    companion object {
        private val chapterRe = Regex("""^第\s*([一二三四五六七八九十百零\d]+)\s*章""")

        fun title(bankName: String, chapters: List<String>): String {
            if (chapters.isEmpty()) return bankName
            val numbers = chapters.map { chapterRe.find(it)?.groupValues?.get(1) }
            val allNumbered = numbers.all { it != null }
            if (allNumbered) {
                val suffix = if (numbers.size == 1) {
                    "第${numbers.single()}章"
                } else {
                    "第" + numbers.joinToString("、") + "章"
                }
                return "$bankName-$suffix"
            }
            return "$bankName-" + chapters.joinToString("、") { chapter ->
                val number = chapterRe.find(chapter)?.groupValues?.get(1)
                if (number == null) chapter else "第${number}章"
            }
        }

        private fun analysisFor(q: PdfQuestion, mode: PdfAnswerMode): String? = when (mode) {
            PdfAnswerMode.DEEP -> q.analysis?.takeIf { q.deepSolved && it.isNotBlank() }
            PdfAnswerMode.MINIMAL -> minimal(q.analysis?.takeIf { q.minimalSolved })
            PdfAnswerMode.ANSWER_ONLY -> null
        }

        private fun minimal(raw: String?): String? = raw?.let(::plainText)
            ?.lineSequence()
            ?.map { it.trim().trimStart('#', '-', '*', ' ') }
            ?.firstOrNull { it.isNotBlank() }

        private val fenceRe = Regex("""^\s*(```|~~~).*$""")
        private val headingRe = Regex("""^\s*#{1,6}\s*""")
        private val bulletRe = Regex("""^\s*[-*+]\s+""")
        private val tableSepRe = Regex("""^\s*\|?[\s:|-]+\|?\s*$""")
        private val boldRe = Regex("""(\*\*|__)(.+?)\1""")
        private val italicRe = Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)""")
        private val codeRe = Regex("""`([^`\n]+)`""")
        private val linkRe = Regex("""\[([^\]]+)]\([^)]*\)""")
        private val tagRe = Regex("""</?[A-Za-z][^>]*>""")
        private val fracRe = Regex("""\\frac\{([^{}]+)\}\{([^{}]+)\}""")
        private val sqrtRe = Regex("""\\sqrt\{([^{}]+)\}""")

        fun plainText(raw: String): String = raw
            .replace("\r\n", "\n")
            .lines()
            .mapNotNull { source ->
                if (fenceRe.matches(source) || tableSepRe.matches(source)) return@mapNotNull null
                var line = source
                    .replace(headingRe, "")
                    .replace(bulletRe, "• ")
                    .replace(fracRe, "($1)/($2)")
                    .replace(sqrtRe, "√($1)")
                    .replace(boldRe, "$2")
                    .replace(italicRe, "$1")
                    .replace(codeRe, "$1")
                    .replace(linkRe, "$1")
                    .replace(tagRe, "")
                    .replace("|", "  ")
                    .trimEnd()
                line = line.trimStart().removePrefix("> ")
                line
            }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
