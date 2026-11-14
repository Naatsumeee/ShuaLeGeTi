package com.examhelper.shua.data.ocr

import android.graphics.Bitmap
import com.benjaminwan.ocrlibrary.LayoutEngine
import kotlin.math.abs

/**
 * 版面分析出来的一个区域（段落级，不是文字行）。
 * 坐标与 OCR 的 [OcrBlock.box] 同一坐标系（原图像素）。
 */
data class LayoutRegion(
    val type: String,
    val box: Box,
    val score: Float
) {
    val isTextual: Boolean get() = type in TEXTUAL_TYPES
    val isNoise: Boolean get() = type in NOISE_TYPES
    val isTable: Boolean get() = type == "table" || type == "table_title"
    val isFormula: Boolean get() = type == "formula" || type == "formula_number"

    companion object {
        /** 参与阅读顺序排版的正文类区域 */
        val TEXTUAL_TYPES = setOf(
            "text", "content", "abstract", "reference",
            "paragraph_title", "figure_title", "doc_title", "aside_text"
        )

        /** 页眉页脚、页码——试卷跑题头会污染题库，识别后直接丢弃 */
        val NOISE_TYPES = setOf("header", "footer", "header_image", "footer_image", "number")
    }
}

/**
 * 一页的版式结论。
 *
 * 注意这里只描述「结构」，不裁剪重识别：PP-DocLayout-S 的框是段落级的，
 * 按框裁开再识别反而丢上下文（实测框常把题干和选项切散）。
 */
data class PageLayout(
    val regions: List<LayoutRegion>,
    /** 栏与栏之间的分割线（原图 x 坐标），升序。单栏时为空 */
    val columnCuts: List<Int>,
    val tableBoxes: List<Box>,
    val formulaBoxes: List<Box>,
    /** 页眉页脚区域，落在这里的 OCR 块会被丢弃 */
    val noiseBoxes: List<Box>,
    /** 模型是否真的跑起来了；false 时调用方走旧启发式 */
    val analyzed: Boolean
) {
    val columnCount: Int get() = columnCuts.size + 1

    companion object {
        val NONE = PageLayout(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)
    }
}

/**
 * 用 PP-DocLayout-S 分析一页的版式，产出栏边界、表格/公式/页眉页脚区域。
 *
 * 设计上的几个结论，都是实测得来的：
 * 1. 模型必须 letterbox 保比例，直接拉伸 480x480 会退化成整页一个大框；
 * 2. raw 输出恒为 100 行（模型自带上限截断），必须先按 score 过滤再 NMS；
 * 3. 栏边界不能简单用「页宽一半」——实测扫描页有 60 个文本块时，
 *    旧启发式把 25 个判成跨栏，导致左栏正文和右栏正文交错乱序；
 *    这里改为对文本块做垂直投影，找中部最宽的空白走廊当栏缝。
 */
object LayoutAnalyzer {

    private const val SCORE_THRESH = 0.3f

    /** 低于此宽度的「走廊」不算栏缝（否则正常段落间距也会被当成两栏） */
    private const val MIN_GUTTER_RATIO = 0.02f

    /**
     * 横跨超过此比例的块不参与栏缝投影（通栏标题、大题说明会把唯一的中缝填平）。
     * 取 0.72 而不是 0.5：双栏页里单栏正文行经常占到 0.45~0.55 页宽，
     * 用 0.5 会把整栏正文当成通栏丢掉，栏缝反而找不出来（实测踩过）。
     */
    private const val SPANNING_SKIP_RATIO = 0.72f

    /** 栏缝必须落在页面中部这一段，避免把页边距当成栏缝 */
    private const val GUTTER_SEARCH_LO = 0.26f
    private const val GUTTER_SEARCH_HI = 0.74f

    /**
     * 用 OCR 行块重算栏缝，返回补上 columnCuts 的版面。
     *
     * 为什么必须分两步：PP-DocLayout-S 是**段落粒度**的检测器，它经常把整页双栏
     * 并成一个横跨全宽的大框（实测合成双栏页只吐 5 个跨越框，缝里全是权重）。
     * OCR 行块粒度细、坐标是原图精度，投影才找得到真正的中缝。
     *
     * 版面模型仍然有用：页眉页脚、表格、公式的定位靠它。
     */
    fun withGutters(layout: PageLayout, boxes: List<Box>, pageWidth: Int): PageLayout {
        if (boxes.isEmpty()) return layout
        val cuts = detectGutters(boxes, pageWidth)
        return if (cuts == layout.columnCuts) layout else layout.copy(columnCuts = cuts)
    }

    fun analyze(engine: LayoutEngine?, bitmap: Bitmap): PageLayout {
        if (engine == null || !engine.available) return PageLayout.NONE
        val rows = runCatching { engine.detect(bitmap, SCORE_THRESH) }.getOrNull()
            ?: return PageLayout.NONE
        if (rows.isEmpty()) return PageLayout.NONE

        val w = bitmap.width
        val h = bitmap.height
        val regions = rows.mapNotNull { r ->
            if (r.size < 6) return@mapNotNull null
            val label = engine.labelName(r[0].toInt())
            val x1 = r[2].toInt().coerceIn(0, w)
            val y1 = r[3].toInt().coerceIn(0, h)
            val x2 = r[4].toInt().coerceIn(0, w)
            val y2 = r[5].toInt().coerceIn(0, h)
            if (x2 - x1 < 4 || y2 - y1 < 4) return@mapNotNull null
            LayoutRegion(label, Box(x1, y1, x2, y2), r[1])
        }
        if (regions.isEmpty()) return PageLayout.NONE

        // 这里先不刻栏——段落粒度框不可靠，交给调用方拿 OCR 行块调 withGutters() 重算
        val cuts = emptyList<Int>()

        // 页眉页脚只在页面上下 12% 内才算数：
        // 模型偶尔把正文里的「一、名词解释」误判成 header，不加这条会把题干删掉。
        val noise = regions.filter {
            it.isNoise && (it.box.centerY < h * 0.12f || it.box.centerY > h * 0.88f)
        }.map { it.box }

        return PageLayout(
            regions = regions,
            columnCuts = cuts,
            tableBoxes = regions.filter { it.isTable }.map { it.box },
            formulaBoxes = regions.filter { it.isFormula }.map { it.box },
            noiseBoxes = noise,
            analyzed = true
        )
    }

    /**
     * 找栏缝：把文本块投影到 x 轴（按高度加权，长段落权重更大），
     * 在页面中部找覆盖率接近 0 的最宽连续区间。
     *
     * 判定为多栏还要满足「切点两侧各有 ≥2 个文本块，且它们占全部文本块的 80% 以上」，
     * 否则只是某一段落碰巧缩进，不能据此刻栏。
     *
     * **必须用 OCR 行块而不是版面框**：PP-DocLayout-S 是段落粒度的，
     * 一页双栏经常被它并成一个横跨全宽的大框（实测合成双栏页只吐 5 个跨越框），
     * 投影后栏缝里全是权重，永远找不到缝。OCR 行块粒度细、坐标是原图精度，才是正确输入。
     *
     * @param boxes 参与投影的文本块（OCR 行块优先；没有时退化为版面框）
     * @param pageWidth 原图宽度
     */
    fun detectGutters(boxes: List<Box>, pageWidth: Int): List<Int> {
        if (boxes.size < 4 || pageWidth <= 0) return emptyList()

        val bins = FloatArray(pageWidth)
        for (b in boxes) {
            val x1 = b.left.coerceIn(0, pageWidth)
            val x2 = b.right.coerceIn(0, pageWidth)
            if (x2 <= x1) continue
            // 横跨大半页的块（通栏标题、大题说明）不参与投影：
            // 它们本来就压在栏缝上，算进去会把唯一的缝填平。
            if ((x2 - x1) > pageWidth * SPANNING_SKIP_RATIO) continue
            val weight = b.height.toFloat().coerceAtLeast(1f)
            for (x in x1 until x2) bins[x] += weight
        }
        val mean = bins.average().toFloat()
        val threshold = mean * 0.02f

        val lo = (pageWidth * GUTTER_SEARCH_LO).toInt()
        val hi = (pageWidth * GUTTER_SEARCH_HI).toInt()
        var bestWidth = 0
        var bestStart = 0
        var bestEnd = 0
        var i = lo
        while (i < hi) {
            if (bins[i] <= threshold) {
                var j = i
                while (j < hi && bins[j] <= threshold) j++
                if (j - i > bestWidth) {
                    bestWidth = j - i
                    bestStart = i
                    bestEnd = j
                }
                i = j
            } else {
                i++
            }
        }

        if (bestWidth < pageWidth * MIN_GUTTER_RATIO) return emptyList()
        val cut = (bestStart + bestEnd) / 2

        val left = boxes.count { it.right <= cut }
        val right = boxes.count { it.left >= cut }
        val covered = left + right
        if (left < 2 || right < 2 || covered < boxes.size * 0.8f) return emptyList()
        return listOf(cut)
    }

    /**
     * 块是否属于页眉页脚。
     *
     * 判据不能是「被 noise 框完全包含」：PP-DocLayout-S 给的框比 OCR 的紧贴框小一圈，
     * 实测「绪论」OCR 框 [1439,81]-[1499,116] 而版面框 [1439,84]-[1492,109]，
     * 右/下各差 7px，严格包含一律不成立 —— 于是页眉页码从来没被滤掉过，
     * 混在正文里被当成题干或上题续行（v1.0.9 的「绪论」出现在文末就是这个原因）。
     *
     * 改为：中心点落在 noise 框内，且 OCR 框被覆盖超过一半。
     * 用覆盖率兜住「恰好压在页眉区域上的长正文行」，不会误删题干。
     */
    fun isNoise(box: Box, layout: PageLayout): Boolean =
        layout.noiseBoxes.any { n ->
            if (!n.contains(box.centerX, box.centerY)) return@any false
            val iw = (minOf(box.right, n.right) - maxOf(box.left, n.left)).coerceAtLeast(0)
            val ih = (minOf(box.bottom, n.bottom) - maxOf(box.top, n.top)).coerceAtLeast(0)
            val inter = iw.toLong() * ih
            val area = box.width.toLong() * box.height
            area > 0 && inter.toFloat() / area >= 0.5f
        }

    /**
     * 章节名常只印在页眉，不能和书名、页码一起当噪声丢掉。
     * 只保留明确带「第X章/节/篇/卷/部分」的页眉。
     */
    fun isChapterHeader(text: String): Boolean =
        Regex("""^\s*第\s*[一二三四五六七八九十百零\d]{1,4}\s*[章节篇卷]""")
            .containsMatchIn(text.trim())

    /** 块是否落在某个表格区域内（按中心点判定） */
    fun inTable(box: Box, layout: PageLayout): Boolean =
        layout.tableBoxes.any { t -> t.contains(box.centerX, box.centerY) }

    /** 某个 x 坐标落在第几栏（0 起） */
    fun columnAt(x: Int, layout: PageLayout): Int {
        var col = 0
        for (cut in layout.columnCuts) if (x >= cut) col++
        return col
    }

    /**
     * 块归属的栏：按**左边界**判定，不按中心点。
     *
     * 按中心点会出大错：一个横跨两栏的标题（left=60、right=840、页宽 900、栏缝 450）
     * 中心正好压在 450 上，会被判成「第 1 栏的普通块」，于是标题掉进右栏正文，
     * 章节标题和它下面的题目就分家了。跨栏与否由调用方比较左边界与右边界所在的栏。
     */
    fun columnOf(box: Box, layout: PageLayout): Int = columnAt(box.left, layout)

    /** 页面是否左右对称分栏（用栏缝到左右页边距的距离判断） */
    fun isBalanced(layout: PageLayout, pageWidth: Int): Boolean {
        if (layout.columnCuts.isEmpty()) return false
        val cut = layout.columnCuts.first()
        val leftMargin = abs(cut - 0f)
        val rightMargin = abs(pageWidth - cut)
        return leftMargin > pageWidth * 0.3f && rightMargin > pageWidth * 0.3f
    }
}
