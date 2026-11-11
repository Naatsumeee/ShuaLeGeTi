package com.examhelper.shua

import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.model.ParsedLine
import com.examhelper.shua.data.parse.AnswerBinder
import com.examhelper.shua.data.parse.LlmQuestionParser
import com.examhelper.shua.data.parse.TextQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真实扫描件的答案覆盖率回归。
 *
 * 素材 `.shots/app_raw13.txt` 是 47 页扫描件（医学遗传学学习指导与习题集_部分2.pdf）
 * 的 App 端 OCR 全文，8 章 / 346 题。它的价值在于「答案区按题型分组、每组
 * 从 1 重新编号」这个排版是真实教材的常态，不是构造出来的。
 *
 * 锁的是**AI 链路**（`AnswerBinder` 的答案回填），而不是离线链路 ——
 * 用户实测「导入后只有 116/347 有答案」走的正是 AI 链路，离线链路一直好的。
 * `.shots/` 不入库，文件缺失时跳过，不让 CI 变红。
 */
class AnswerCoverageRealFileTest {

    private fun rawFile(): File? = listOf(
        File("../.shots/app_raw13.txt"),
        File(".shots/app_raw13.txt")
    ).firstOrNull { it.exists() }

    /**
     * 绑定层直测：不经过 `finalizeLines` 的去重。
     *
     * 为什么绕开去重：OCR 全文里存在整题重复（同一道「46,XX,inv…」在第 19 位和
     * 第 39 位各出现一次），`findSame` 会把它们合并成一条并保留更长的题干，
     * 题号随之易主。那是去重的固有行为，与答案绑定无关，混在一个断言里会让
     * 「错填」变成假阳性。
     */
    @Test
    fun `真实扫描件的客观题答案按题号精确绑定且无错填`() {
        val file = rawFile() ?: return
        val text = file.readText(Charsets.UTF_8)
        val truth = TextQuestionParser.parse(text)
        if (truth.size < 100) return

        val items = truth.map { q ->
            AnswerBinder.Item(
                chapter = q.chapter,
                // AI 输出的题干通常不带题号，这里模拟成剥掉题号的样子
                stem = q.stem.replace(
                    Regex("""^\s*[（(]?\s*\d{1,4}\s*[）)]?\s*[、.．,，:：)）]\s*"""), ""
                ),
                optionKeys = (q.options + q.sharedOptions).map { o -> o.key }.toSet(),
                number = q.sourceNumber,
                objectiveType = q.type == QuestionType.SINGLE ||
                    q.type == QuestionType.MULTIPLE || q.type == QuestionType.JUDGE
            )
        }

        val bound = AnswerBinder.bind(text, items)
        val objectiveCount = items.count { it.objectiveType }
        val objectiveBound = items.indices.count { items[it].objectiveType && it in bound }
        println("绑定 ${objectiveBound}/${objectiveCount} 道客观题")

        // 只校验客观题：主观题是内容匹配，正确性由 AnswerBinderTest 的内容用例守。
        var wrong = 0
        items.forEachIndexed { i, item ->
            if (!item.objectiveType) return@forEachIndexed
            val got = bound[i] ?: return@forEachIndexed
            val expected = truth[i].answer ?: return@forEachIndexed
            if (got != expected) {
                wrong++
                println("  错填: [${item.chapter}] no=${item.number} got=$got want=$expected")
            }
        }
        assertEquals("有 $wrong 道客观题被填了错误答案", 0, wrong)

        // 第七章实测答案表 1..25 与本组客观题数量完全对上，是「按题号精确绑定」
        // 最干净的样本，单独盯一条，防止序号兜底逻辑被误改。
        assertEquals("第七章客观题应全部绑定", 15, items.indices.count {
            items[it].chapter?.contains("七章") == true && items[it].objectiveType && it in bound
        })
    }

    /** 端到端：走 `finalizeLines`（含去重），覆盖率不能掉回修复前的个位数 */
    @Test
    fun `AI 链路端到端答案覆盖率不低于八成`() {
        val file = rawFile() ?: return
        val text = file.readText(Charsets.UTF_8)
        val truth = TextQuestionParser.parse(text)
        if (truth.size < 100) return

        val lines = truth.mapIndexed { i, q ->
            ParsedLine(
                chunkIndex = 0, indexInChunk = i,
                chapter = q.chapter, type = q.type.name, subtype = q.subtype,
                number = q.sourceNumber,
                stem = q.stem.replace(
                    Regex("""^\s*[（(]?\s*\d{1,4}\s*[）)]?\s*[、.．,，:：)）]\s*"""), ""
                ),
                sharedStem = q.sharedStem, sharedOptions = q.sharedOptions,
                options = q.options, answer = null, analysis = null, page = q.sourcePage
            )
        }

        val after = LlmQuestionParser.finalizeLines(lines, text)
        val covered = after.count { !it.answer.isNullOrBlank() }
        val ratio = covered.toDouble() / after.size
        println("端到端答案覆盖 $covered/${after.size} = ${(ratio * 100).toInt()}%")
        after.groupBy { it.chapter }.forEach { (ch, l) ->
            println("  [$ch] ${l.count { !it.answer.isNullOrBlank() }}/${l.size}")
        }

        // 修复前：31/336（9%），真机实测 116/347（33%）。
        assertTrue("端到端答案覆盖率过低：$covered/${after.size}", ratio >= 0.80)
    }
}
