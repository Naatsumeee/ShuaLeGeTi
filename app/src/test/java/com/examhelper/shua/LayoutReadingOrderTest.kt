package com.examhelper.shua


import com.examhelper.shua.data.ocr.Box
import com.examhelper.shua.data.ocr.LayoutAnalyzer
import com.examhelper.shua.data.ocr.LayoutRegion
import com.examhelper.shua.data.ocr.MlKitOcrEngine
import com.examhelper.shua.data.ocr.OcrBlock
import com.examhelper.shua.data.ocr.PageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版面分析 → 阅读顺序重排 的行为约定。
 *
 * 这些用例守护的是「真实扫描试卷上的错序问题」：
 * 旧启发式用「块在页宽一半的哪一侧」判断栏，实测某页 60 个文本块里 25 个被判成跨栏，
 * 结果左栏正文和右栏正文交错，题目被切碎、答案对不上。
 */


class LayoutReadingOrderTest {

    private fun block(text: String, l: Int, t: Int, r: Int, b: Int) =
        OcrBlock(text, Box.of(l, t, r, b))

    private fun region(type: String, l: Int, t: Int, r: Int, b: Int) =
        LayoutRegion(type, Box.of(l, t, r, b), 0.9f)

    @Test
    fun twoColumnPageUsesGutterNotPageCenter() {
        // 左栏 x 60..430，右栏 x 470..840，页宽 900，栏缝在 450
        val layout = PageLayout(
            regions = emptyList(),
            columnCuts = listOf(450),
            tableBoxes = emptyList(),
            formulaBoxes = emptyList(),
            noiseBoxes = emptyList(),
            analyzed = true
        )
        val blocks = listOf(
            block("左1", 60, 100, 430, 140),
            block("右1", 470, 100, 840, 140),
            block("左2", 60, 160, 430, 200),
            block("右2", 470, 160, 840, 200)
        )
        val text = MlKitOcrEngine.rebuildReadingOrder(blocks, 900, layout)
        // 左栏两行连在一起、右栏两行连在一起，而不是 左1/右1/左2/右2 交错
        assertEquals(listOf("左1\n左2\n右1\n右2").joinToString("\n"), text)
    }

    @Test
    fun spanningBlockGoesFirst() {
        val layout = PageLayout(
            regions = emptyList(),
            columnCuts = listOf(450),
            tableBoxes = emptyList(),
            formulaBoxes = emptyList(),
            noiseBoxes = emptyList(),
            analyzed = true
        )
        val blocks = listOf(
            block("左栏正文", 60, 200, 430, 240),
            block("右栏正文", 470, 200, 840, 240),
            block("一、名词解释（本大题共5小题）", 60, 100, 840, 140)
        )
        val text = MlKitOcrEngine.rebuildReadingOrder(blocks, 900, layout)
        assertTrue(text.startsWith("一、名词解释"))
        assertTrue(text.indexOf("左栏正文") < text.indexOf("右栏正文"))
    }

    @Test
    fun headerAndFooterAreDropped() {
        val layout = PageLayout(
            regions = emptyList(),
            columnCuts = emptyList(),
            tableBoxes = emptyList(),
            formulaBoxes = emptyList(),
            noiseBoxes = listOf(Box.of(0, 0, 900, 90), Box.of(0, 1200, 900, 1285)),
            analyzed = true
        )
        val blocks = listOf(
            block("医学遗传学学习指导与习题集", 100, 20, 800, 60),
            block("1. 题干正文", 60, 300, 840, 340),
            block("128", 440, 1220, 470, 1260)
        )
        val text = MlKitOcrEngine.rebuildReadingOrder(blocks, 900, layout)
        assertEquals("1. 题干正文", text)
    }

    @Test
    fun singleColumnFallsBackToLineSorting() {
        // 没有栏缝 → 走行聚类，同一行并排的选项要按 left 排序（不能 B 在 A 前面）
        val layout = PageLayout(
            regions = emptyList(),
            columnCuts = emptyList(),
            tableBoxes = emptyList(),
            formulaBoxes = emptyList(),
            noiseBoxes = emptyList(),
            analyzed = true
        )
        val blocks = listOf(
            block("B. 高血压", 500, 400, 700, 440),
            block("A. 支气管哮喘", 100, 401, 460, 441),
            block("题干", 100, 300, 800, 340)
        )
        val text = MlKitOcrEngine.rebuildReadingOrder(blocks, 900, layout)
        assertEquals("题干\nA. 支气管哮喘\nB. 高血压", text)
    }

    @Test
    fun noLayoutFallsBackToLegacyHeuristic() {
        // 版面分析不可用时（ML Kit 引擎）旧行为必须完全保留
        val blocks = listOf(
            block("左1", 60, 100, 430, 140),
            block("左2", 60, 160, 430, 200),
            block("右1", 500, 100, 860, 140),
            block("右2", 500, 160, 860, 200)
        )
        val text = MlKitOcrEngine.rebuildReadingOrder(blocks, 900, PageLayout.NONE)
        assertEquals("左1\n左2\n右1\n右2", text)
    }

    @Test
    fun columnOfUsesCuts() {
        val layout = PageLayout(
            emptyList(), listOf(300, 600), emptyList(), emptyList(), emptyList(), true
        )
        assertEquals(0, LayoutAnalyzer.columnOf(Box.of(10, 0, 200, 50), layout))
        assertEquals(1, LayoutAnalyzer.columnOf(Box.of(320, 0, 500, 50), layout))
        assertEquals(2, LayoutAnalyzer.columnOf(Box.of(650, 0, 880, 50), layout))
    }

    @Test
    fun regionClassification() {
        assertTrue(region("text", 0, 0, 10, 10).isTextual)
        assertTrue(region("table", 0, 0, 10, 10).isTable)
        assertTrue(region("formula", 0, 0, 10, 10).isFormula)
        assertTrue(region("footer", 0, 0, 10, 10).isNoise)
        assertFalse(region("image", 0, 0, 10, 10).isTextual)
    }

    @Test
    fun noiseDetectionToleratesTwoPixels() {
        val layout = PageLayout(
            emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(Box.of(0, 0, 900, 90)), true
        )
        assertTrue(LayoutAnalyzer.isNoise(Box.of(10, 2, 800, 88), layout))
        assertFalse(LayoutAnalyzer.isNoise(Box.of(10, 100, 800, 140), layout))
    }

    @Test
    fun detectGuttersFindsGutterFromOcrLines() {
        // 真实双栏：左栏 x 40..410，右栏 x 470..860，页宽 900 -> 缝在 440 上下
        val boxes = ArrayList<Box>()
        for (i in 0 until 12) {
            val y = 100 + i * 60
            boxes += Box.of(40, y, 410, y + 40)
            boxes += Box.of(470, y, 860, y + 40)
        }
        val cuts = LayoutAnalyzer.detectGutters(boxes, 900)
        assertEquals(1, cuts.size)
        assertTrue("cut=${cuts[0]}", cuts[0] in 410..470)
    }

    @Test
    fun detectGuttersIgnoresWideHeading() {
        // 通栏标题横跨全宽：不能因为它把缝填平就认定单栏
        val boxes = ArrayList<Box>()
        boxes += Box.of(40, 40, 860, 80)
        for (i in 0 until 12) {
            val y = 120 + i * 60
            boxes += Box.of(40, y, 410, y + 40)
            boxes += Box.of(470, y, 860, y + 40)
        }
        val cuts = LayoutAnalyzer.detectGutters(boxes, 900)
        assertEquals(1, cuts.size)
        assertTrue("cut=${cuts[0]}", cuts[0] in 410..470)
    }

    @Test
    fun detectGuttersGivesUpOnSingleColumn() {
        val boxes = ArrayList<Box>()
        for (i in 0 until 15) {
            val y = 100 + i * 60
            boxes += Box.of(40, y, 860, y + 40)
        }
        assertTrue(LayoutAnalyzer.detectGutters(boxes, 900).isEmpty())
    }

    @Test
    fun withGuttersOverridesModelCuts() {
        val layout = PageLayout(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), true)
        val boxes = ArrayList<Box>()
        for (i in 0 until 12) {
            val y = 100 + i * 60
            boxes += Box.of(40, y, 410, y + 40)
            boxes += Box.of(470, y, 860, y + 40)
        }
        val fixed = LayoutAnalyzer.withGutters(layout, boxes, 900)
        assertEquals(1, fixed.columnCuts.size)
    }

    @Test
    fun twoColumnReadingOrderFromRealLineBoxes() {
        // 端到端：行块 -> 栏缝 -> 阅读顺序，右栏不能插到左栏中间
        val boxes = ArrayList<Box>()
        val texts = ArrayList<String>()
        for (i in 0 until 6) {
            val y = 100 + i * 60
            boxes += Box.of(40, y, 410, y + 40); texts += "左${i + 1}"
            boxes += Box.of(470, y, 860, y + 40); texts += "右${i + 1}"
        }
        val blocks = texts.mapIndexed { i, t -> OcrBlock(t, boxes[i]) }
        val layout = LayoutAnalyzer.withGutters(PageLayout.NONE, boxes, 900)
        val text = MlKitOcrEngine.rebuildReadingOrder(blocks, 900, layout)
        assertEquals(listOf("左1", "左2", "左3", "左4", "左5", "左6", "右1", "右2", "右3", "右4", "右5", "右6").joinToString("\n"), text)
    }

    @Test
    fun emptyBlocksProduceEmptyText() {
        assertEquals("", MlKitOcrEngine.rebuildReadingOrder(emptyList(), 900, PageLayout.NONE))
    }
}
