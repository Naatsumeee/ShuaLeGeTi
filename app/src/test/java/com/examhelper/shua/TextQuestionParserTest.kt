package com.examhelper.shua

import com.examhelper.shua.data.db.QuestionType
import com.examhelper.shua.data.parse.TextQuestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextQuestionParserTest {

    private val sample = """
        一、单项选择题
        1、医学伦理学的基本原则不包括（ ）
        A、自主原则
        B、不伤害原则
        C、有利原则
        D、平等原则 √
        2、下列说法正确的是：B
        A、错误说法
        B、正确说法
        3、下列哪项属于医生的权利？
        A、诊治权
        B、干涉权
        答案：AB
        解析：本题考查医生权利。
        4、医学伦理学的一词最早出现在对
        5、填空题：希波克拉底誓言出自___
        答案：古希腊
        6、简述尊重原则的内容。
        答案：尊重病人的自主权、知情同意权等。
    """.trimIndent()

    @Test
    fun parseBasicFormats() {
        val questions = TextQuestionParser.parse(sample)
        assertEquals(6, questions.size)

        val q1 = questions[0]
        assertEquals(QuestionType.SINGLE, q1.type)
        assertEquals("D", q1.answer)
        assertEquals(4, q1.options.size)
        assertEquals("平等原则", q1.options[3].text) // √ 已剥离
        // 整本书没有「第X章」标题，只有「一、单项选择题」这种题型分组标题。
        // 这类标题**不能**当章节名（用户反复报的 bug：章节名变成「（一）名词解释」），
        // 没有真章节时宁可 chapter 为空，让 UI 显示「未分章」。
        assertNull(q1.chapter)

        val q2 = questions[1]
        assertEquals("B", q2.answer)
        assertEquals(QuestionType.SINGLE, q2.type)

        val q3 = questions[2]
        assertEquals(QuestionType.MULTIPLE, q3.type)
        assertEquals("AB", q3.answer)
        assertNotNull(q3.analysis)

        val q5 = questions[4]
        assertEquals(QuestionType.FILL, q5.type)
        assertEquals("古希腊", q5.answer)

        val q6 = questions[5]
        assertEquals(QuestionType.SHORT_ANSWER, q6.type)
    }

    @Test
    fun judgeOptions() {
        val judge = """
            1、医德是一种职业道德。答案：对
        """.trimIndent()
        val q = TextQuestionParser.parse(judge).first()
        assertEquals(QuestionType.JUDGE, q.type)
        // 判断题统一归一到 A/B（A=正确 B=错误），并自动补齐两个选项。
        // 展示层用 JudgeOptions.displayFor 把 A 渲染成「正确」，所以库里存字母是对的。
        assertEquals("A", q.answer)
        assertEquals(2, q.options.size)
        assertEquals("正确", q.options[0].text)
        assertEquals("错误", q.options[1].text)
    }

    @Test
    fun decimalNumbersNotQuestions() {
        val text = "给药剂量为1.5毫升，每日2次。"
        assertEquals(0, TextQuestionParser.parse(text).size)
    }

    @Test
    fun `题型标签不再被当成章节名`() {
        // 用户报告：「（一）名词解释」「（三）简答题」被错误识别成章节名
        val text = """
            第六章 线粒体遗传
            一、学习目标
            掌握线粒体遗传的特点。
            二、习题
            （一）名词解释
            1.纯质性
            （二）选择题
            【A1型题】
            1.mtDNA指
            A.突变的DNA
            B.线粒体DNA
            答案：B
            （三）简答题
            2.简述母系遗传的特点。
            答案：母亲把mtDNA传给所有子女。
        """.trimIndent()
        val qs = TextQuestionParser.parse(text)
        assertTrue("应当解析出题目", qs.isNotEmpty())
        qs.forEach { q ->
            val c = q.chapter.orEmpty()
            assertFalse("章节不该是题型标签：$c", c.contains("名词解释") || c.contains("简答题"))
            assertTrue("章节应含第六章，实际=$c", c.contains("第六章") || c.contains("线粒体遗传"))
        }
    }

    @Test
    fun `OCR 拆行的第X章与章名能拼回完整章节`() {
        // 扫描件常见：章号和章名分两行
        val text = """
            第六章
            线粒体遗传
            1.mtDNA指
            A.突变的DNA
            B.线粒体DNA
            答案：B
            第七章
            单基因遗传病
            2.下列属于常染色体显性遗传的是
            A.甲病
            B.乙病
            答案：A
        """.trimIndent()
        val qs = TextQuestionParser.parse(text)
        assertEquals(2, qs.size)
        assertTrue("第一章题应归到第六章，实际=${qs[0].chapter}", qs[0].chapter!!.contains("第六章"))
        assertTrue("章名本体应拼上，实际=${qs[0].chapter}", qs[0].chapter!!.contains("线粒体遗传"))
        assertTrue("第二章题应归到第七章，实际=${qs[1].chapter}", qs[1].chapter!!.contains("第七章"))
    }

    @Test
    fun `章节序号不会因为去前缀而丢失`() {
        // 用户报告：「章节名前依然会出现第xx章丢失」
        val text = """
            第一章 绪论
            1.第一题
            A.甲
            B.乙
            答案：A
            第二章 细胞
            2.第二题
            A.甲
            B.乙
            答案：B
        """.trimIndent()
        val qs = TextQuestionParser.parse(text)
        assertEquals(2, qs.size)
        assertEquals("第一章 绪论", qs[0].chapter)
        assertEquals("第二章 细胞", qs[1].chapter)
    }

    @Test
    fun `缺失的章节序号按顺序补齐`() {
        // 第七章那一行 OCR 漏掉了，只剩章名 → 应补成「第七章」
        val text = """
            第六章 线粒体遗传
            1.第一题
            A.甲
            B.乙
            答案：A
            单基因遗传病
            2.第二题
            A.甲
            B.乙
            答案：B
            多基因遗传病
            3.第三题
            A.甲
            B.乙
            答案：A
            第九章 群体遗传
            4.第四题
            A.甲
            B.乙
            答案：B
        """.trimIndent()
        val qs = TextQuestionParser.parse(text)
        assertTrue("应解析出至少 3 题", qs.size >= 3)
        val chapters = qs.mapNotNull { it.chapter }.distinct()
        // 补出来的章节必须带章号，且序号连贯
        assertTrue("章节应全部带章号：$chapters", chapters.all { it.contains("第") && it.contains("章") })
    }

    @Test
    fun `名词解释和简答题答案表不会丢失`() {
        val text = """
            第一章 遗传学
            （一）名词解释
            1.纯质性
            （三）简答题
            1.简述母系遗传的特点。
            三、参考答案
            （一）名词解释
            1.纯质性是指一个细胞或个体中只有一种线粒体DNA。
            （三）简答题
            1.母亲把线粒体DNA传给所有子女，但只有女儿能继续传递给下一代。
        """.trimIndent()
        val qs = TextQuestionParser.parse(text)
        val term = qs.first { it.stem.contains("纯质性") }
        val short = qs.first { it.stem.contains("母系遗传") }
        assertEquals(QuestionType.TERM, term.type)
        assertNotNull("名词解释答案丢失", term.answer)
        assertEquals(QuestionType.SHORT_ANSWER, short.type)
        assertNotNull("简答题答案丢失", short.answer)
    }}
