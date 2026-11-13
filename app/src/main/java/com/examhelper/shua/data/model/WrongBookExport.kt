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
    ANSWER_ONLY,
    CONCENTRATED,
    NONE
}

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
    val deepSolved: Boolean
)

data class WrongBookExport(
    val source: PdfExportSource,
    val questions: List<PdfQuestion>
) {
    fun select(request: PdfExportRequest): List<PdfQuestion> = questions.filter { q ->
        q.bankId == request.bankId &&
            (request.scope == PdfExportScope.BANK || q.chapter in request.chapters)
    }

    fun answerLines(mode: PdfAnswerMode): List<String> = questions.mapIndexed { i, q ->
        buildString {
            append(i + 1).append(".【答案】").append(q.answer?.takeIf { it.isNotBlank() } ?: "无")
            if (mode == PdfAnswerMode.CONCENTRATED) {
                append('\n').append("【解析】").append(q.analysis?.takeIf { it.isNotBlank() } ?: "无")
            }
        }
    }

    fun questionBlocks(mode: PdfAnswerMode): List<String> = questions.mapIndexed { i, q ->
        buildString {
            append(i + 1).append(". ").append(q.stem)
            q.options.forEach { append('\n').append(it) }
            if (mode != PdfAnswerMode.NONE && mode != PdfAnswerMode.CONCENTRATED) {
                append('\n').append("【答案】").append(q.answer?.takeIf { it.isNotBlank() } ?: "无")
                val analysis = when (mode) {
                    PdfAnswerMode.DEEP -> q.analysis?.takeIf { q.deepSolved && it.isNotBlank() }
                    PdfAnswerMode.MINIMAL -> minimal(q.analysis)
                    else -> null
                }
                if (mode == PdfAnswerMode.DEEP || mode == PdfAnswerMode.MINIMAL) {
                    append('\n').append("【解析】").append(analysis ?: "无")
                }
            }
        }
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

        private fun minimal(raw: String?): String? = raw
            ?.lineSequence()
            ?.map { it.trim().trimStart('#', '-', '*', ' ') }
            ?.firstOrNull { it.isNotBlank() }
            ?.take(30)
    }
}
