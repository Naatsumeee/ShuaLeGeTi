package com.examhelper.shua.data.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OCR 识别出的文本块（带位置，用于分栏重排） */
data class OcrBlock(
    val text: String,
    val box: Box?
)

data class OcrPageResult(
    /** 分栏重排后的全文 */
    val text: String,
    val blocks: List<OcrBlock>,
    val pageWidth: Int,
    val pageHeight: Int,
    /** 版面分析结论；不做版面分析的引擎（ML Kit）保持 [PageLayout.NONE] */
    val layout: PageLayout = PageLayout.NONE
)

suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
}

/** OCR 引擎统一接口（ML Kit / RapidOCR） */
interface OcrProvider {
    suspend fun recognize(bitmap: Bitmap): OcrPageResult
}

/**
 * ML Kit 中文 OCR（bundled，完全离线）。
 * 关键点：识别后做双栏阅读顺序重排（扫描版试卷常为左右双栏）。
 */
class MlKitOcrEngine : OcrProvider {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap): OcrPageResult {
        val text: Text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
        val blocks = text.textBlocks.mapNotNull { b ->
            OcrBlock(
                b.text,
                b.boundingBox?.let { Box.of(it.left, it.top, it.right, it.bottom) }
            )
        }
        return OcrPageResult(
            text = rebuildReadingOrder(blocks, bitmap.width),
            blocks = blocks,
            pageWidth = bitmap.width,
            pageHeight = bitmap.height
        )
    }

    companion object {
        /**
         * 阅读顺序重排。
         *
         * 有版面分析结果时（layout.analyzed == true）用模型给出的栏边界切栏：
         * 这才是准确的做法——旧启发式「块在页宽一半的哪一侧」在真实试卷上错得离谱，
         * 实测某页 60 个文本块里有 25 个被判成跨栏，导致左右两栏正文交错乱序。
         *
         * 没有版面分析时退回旧的双栏启发式（保持兼容：ML Kit 引擎没有版面模型）。
         */
        fun rebuildReadingOrder(
            blocks: List<OcrBlock>,
            pageWidth: Int,
            layout: PageLayout = PageLayout.NONE
        ): String {
            if (blocks.isEmpty()) return ""

            // 丢弃页眉页脚：扫描试卷的页眉（书名/章节名）与页码如果混进来，
            // 会被当成题干或上一题的续行，污染题库。
            val kept = if (layout.noiseBoxes.isEmpty()) blocks
            else blocks.filter { b -> b.box == null || !LayoutAnalyzer.isNoise(b.box!!, layout) }
            if (kept.isEmpty()) return ""

            if (pageWidth <= 0) return kept.joinToString("\n") { it.text }

            // 只要版面分析跑通了，就必须完全听它的——包括「它就是单栏」这个结论。
            // 曾经的写法是 `analyzed && columnCuts.isNotEmpty()` 才走版面分支，
            // 于是单栏页（cuts 为空）掉进了下面的旧启发式，而旧启发式在真实单栏试卷上
            // 会把「题干+三列并排选项」误判成左右双栏（实测 62 块里 left=34/right=22），
            // 结果整页选项被劈成两半、后半页的选项全被甩到文末——v1.0.9 的乱码事故。
            // 单栏时 orderedByLayout 的 byCol 只有一个桶，等价于「全部按行排序」，正是要的结果。
            if (layout.analyzed) {
                return orderedByLayout(kept, layout)
            }

            // 没有版面模型时（ML Kit 引擎）不能退回「块在页宽哪一侧」的老启发式——
            // 它同样会在单栏试卷上把三列并排的选项劈成左右两半（与 v1.0.9 事故同因）。
            // detectGutters 是纯几何计算（x 轴垂直投影找空白走廊），不依赖模型，
            // 这里直接用它算栏缝；找不到缝就按行排，正好是单栏该有的结果。
            val gutter = LayoutAnalyzer.detectGutters(kept.mapNotNull { it.box }, pageWidth)
            if (gutter.isNotEmpty()) {
                return orderedByLayout(kept, layout.copy(columnCuts = gutter))
            }
            return sortByLines(kept).joinToString("\n") { it.text }
        }

        /**
         * 按版面分析结果排列：先跨栏块，再从左到右逐栏输出，栏内按行。
         *
         * 跨栏块（越过栏缝的标题、横跨两栏的大题说明）单独提到最前面——
         * 试卷里跨栏的一定是章节标题或大题说明，本来就该排在两侧正文之前。
         */
        private fun orderedByLayout(blocks: List<OcrBlock>, layout: PageLayout): String {
            val spanning = mutableListOf<OcrBlock>()
            val byCol = Array(layout.columnCuts.size + 1) { mutableListOf<OcrBlock>() }

            for (b in blocks) {
                val box = b.box
                if (box == null) {
                    spanning.add(b)
                    continue
                }
                val startCol = LayoutAnalyzer.columnOf(box, layout)
                val endCol = LayoutAnalyzer.columnAt(box.right - 1, layout)
                if (startCol != endCol) spanning.add(b) else byCol[startCol].add(b)
            }

            val sb = StringBuilder()
            fun append(text: String) {
                if (text.isBlank()) return
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text)
            }

            append(sortByLines(spanning).joinToString("\n") { it.text })
            for (col in byCol.indices) {
                append(sortByLines(byCol[col]).joinToString("\n") { it.text })
            }
            return sb.toString()
        }

        /**
         * 行聚类排序：先把块聚成"视觉行"，行内按 left 从左到右，行间按 top 从上到下。
         *
         * 修复的 bug：同一行并排的两个选项（如 "A. 支气管哮喘    B. 高血压"）在 OCR 结果里
         * 每块 top 常有 1~2px 抖动，若直接用 compareBy(top, left) 全量排序，会退化成
         * "B. 高血压 / A. 支气管哮喘" 这种按列优先的错乱，导致选项内容与答案字母错位。
         */
        private fun sortByLines(blocks: List<OcrBlock>): List<OcrBlock> {
            val withBox = blocks.filter { it.box != null }
            val noBox = blocks.filter { it.box == null }
            if (withBox.isEmpty()) return blocks
            val rows = mutableListOf<MutableList<OcrBlock>>()
            for (b in withBox.sortedBy { it.box!!.centerY }) {
                val box = b.box!!
                val row = rows.firstOrNull { r -> r.any { sameLine(it.box!!, box) } }
                if (row != null) row.add(b) else rows.add(mutableListOf(b))
            }
            return rows.sortedBy { r -> r.minOf { it.box!!.top } }
                .flatMap { r -> r.sortedBy { it.box!!.left } } + noBox
        }

        /** 两块视为同一行：纵向重叠超过较矮者高度的一半 */
        private fun sameLine(a: Box, b: Box): Boolean {
            val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (overlap <= 0) return false
            return overlap.toFloat() >= minOf(a.height, b.height) * 0.5f
        }
    }
}
