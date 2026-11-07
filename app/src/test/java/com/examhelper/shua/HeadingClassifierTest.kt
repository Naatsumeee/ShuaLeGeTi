package com.examhelper.shua

import com.examhelper.shua.data.parse.HeadingClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节名里混进题型分组标题的回归护栏。
 *
 * 用户反复报「导入题库后章节名仍然带（一）名词解释，这个 bug 就没修好过」，
 * 这里钉死三条：
 * 1. 纯题型标题绝不当章节名（宁可 null）；
 * 2. 「章名（一）名词解释」这种拼在一起的，要削掉尾巴只留章名；
 * 3. 「第三章 名词解释」只剩章号时只留「第三章」。
 */
class HeadingClassifierTest {

    @Test
    fun `纯题型标题被识别`() {
        assertTrue(HeadingClassifier.isPureTypeHeading("（一）名词解释"))
        assertTrue(HeadingClassifier.isPureTypeHeading("一、单项选择题"))
        assertTrue(HeadingClassifier.isPureTypeHeading("二、多项选择题"))
        assertTrue(HeadingClassifier.isPureTypeHeading("三、判断题"))
        assertTrue(HeadingClassifier.isPureTypeHeading("四、简答题"))
        assertTrue(HeadingClassifier.isPureTypeHeading("五、论述题"))
        assertTrue(HeadingClassifier.isPureTypeHeading("B1型题"))
        assertTrue(HeadingClassifier.isPureTypeHeading("共用题干"))
    }

    @Test
    fun `真章节标题不会被误判成题型`() {
        assertFalse(HeadingClassifier.isPureTypeHeading("第一章 绪论"))
        assertFalse(HeadingClassifier.isPureTypeHeading("第三章 遗传病的分类"))
        assertFalse(HeadingClassifier.isPureTypeHeading("第九章 群体遗传"))
        // 带章号的即使尾巴是题型，也算「有章号」，由 cleanChapter 单独处理
        assertFalse(HeadingClassifier.isPureTypeHeading("第三章 名词解释"))
    }

    @Test
    fun `章名后拼接的题型尾巴被削掉`() {
        assertEquals(
            "第一章 绪论",
            HeadingClassifier.cleanChapter("第一章 绪论（一）名词解释")
        )
        assertEquals(
            "第一章 绪论",
            HeadingClassifier.cleanChapter("第一章 绪论（一）名词解释（二）单项选择题")
        )
        assertEquals(
            "第三章 遗传病的分类",
            HeadingClassifier.cleanChapter("第三章 遗传病的分类 一、单项选择题")
        )
        assertEquals(
            "第二章 染色体病",
            HeadingClassifier.cleanChapter("## 第二章 染色体病（一）名词解释")
        )
    }

    @Test
    fun `纯题型标题清洗后为 null`() {
        assertNull(HeadingClassifier.cleanChapter("（一）名词解释"))
        assertNull(HeadingClassifier.cleanChapter("一、单项选择题"))
        assertNull(HeadingClassifier.cleanChapter("三、判断题"))
        assertNull(HeadingClassifier.cleanChapter("  "))
        assertNull(HeadingClassifier.cleanChapter(""))
    }

    @Test
    fun `章号加题型只保留章号`() {
        assertEquals("第三章", HeadingClassifier.cleanChapter("第三章 名词解释"))
        assertEquals("第十章", HeadingClassifier.cleanChapter("第十章 简答题"))
    }

    @Test
    fun `正常章节名原样保留`() {
        assertEquals("第一章 绪论", HeadingClassifier.cleanChapter("第一章 绪论"))
        assertEquals("第九章 群体遗传与进化", HeadingClassifier.cleanChapter("第九章 群体遗传与进化"))
        // 没有章号的自由命名分组也要保住（不能因为不含关键词就丢掉）
        assertEquals("呼吸系统", HeadingClassifier.cleanChapter("呼吸系统"))
        assertEquals("Chapter 1", HeadingClassifier.cleanChapter("Chapter 1"))
    }

    @Test
    fun `章名本身含题型字样但不该被削`() {
        // 「选择题的解题技巧」这类章节名虽然含关键词，但超过长度阈值 / 不是分组标题形态
        assertEquals(
            "选择题的解题技巧与常见陷阱分析",
            HeadingClassifier.cleanChapter("选择题的解题技巧与常见陷阱分析")
        )
    }

    @Test
    fun `长句不被当成题型标题`() {
        assertFalse(HeadingClassifier.isTypeHeading("下列关于选择题的说法中正确的是"))
        assertFalse(HeadingClassifier.isPureTypeHeading("下列关于判断题的说法中错误的是"))
    }

    @Test
    fun `章内小节骨架不当章节名`() {
        // 教辅书每章都是「一、学习目标 / 二、习题 / 三、参考答案」这套骨架，
        // 整本没有「第X章」时它们会顶上来，题库里就凭空多出「学习目标」「习题」这些章
        assertTrue(HeadingClassifier.isNonChapterHeading("一、学习目标"))
        assertTrue(HeadingClassifier.isNonChapterHeading("二、习题"))
        assertTrue(HeadingClassifier.isNonChapterHeading("三、参考答案"))
        assertTrue(HeadingClassifier.isNonChapterHeading("（四）思考题"))
        assertTrue(HeadingClassifier.isNonChapterHeading("本章小结"))
        assertNull(HeadingClassifier.cleanChapter("一、学习目标"))
        assertNull(HeadingClassifier.cleanChapter("三、参考答案"))
        // 带章号的放行：「第十二章 课后习题」是真章节
        assertFalse(HeadingClassifier.isNonChapterHeading("第十二章 课后习题"))
        assertEquals(
            "第十二章 课后习题",
            HeadingClassifier.cleanChapter("第十二章 课后习题")
        )
    }
}
