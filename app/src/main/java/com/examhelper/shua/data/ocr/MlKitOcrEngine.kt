package com.examhelper.shua.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
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
    val box: Rect?
)

data class OcrPageResult(
    /** 分栏重排后的全文 */
    val text: String,
    val blocks: List<OcrBlock>,
    val pageWidth: Int,
    val pageHeight: Int
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
            OcrBlock(b.text, b.boundingBox)
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
         * 双栏检测与重排：
         * 若块明显分布在页面两侧（左列块右边界 < 55% 宽，右列块左边界 > 45% 宽），
         * 则先输出左列（按 top 排序），再输出右列；否则按 (top, left) 排序。
         */
        fun rebuildReadingOrder(blocks: List<OcrBlock>, pageWidth: Int): String {
            if (blocks.isEmpty()) return ""
            if (pageWidth <= 0) return blocks.joinToString("\n") { it.text }

            val mid = pageWidth / 2.0
            val left = blocks.filter { it.box != null && it.box!!.right < mid * 1.1 }
            val right = blocks.filter { it.box != null && it.box!!.left > mid * 0.9 }
            val spanning = blocks.filter { it.box == null || (it.box!!.right >= mid * 1.1 && it.box!!.left <= mid * 0.9) }

            val twoColumn = left.size >= 2 && right.size >= 2 &&
                left.size + right.size >= blocks.size * 7 / 10

            return if (twoColumn) {
                val header = sortByLines(spanning).joinToString("\n") { it.text }
                val leftText = sortByLines(left).joinToString("\n") { it.text }
                val rightText = sortByLines(right).joinToString("\n") { it.text }
                listOf(header, leftText, rightText).filter { it.isNotBlank() }.joinToString("\n")
            } else {
                sortByLines(blocks).joinToString("\n") { it.text }
            }
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
            for (b in withBox.sortedBy { it.box!!.centerY() }) {
                val box = b.box!!
                val row = rows.firstOrNull { r -> r.any { sameLine(it.box!!, box) } }
                if (row != null) row.add(b) else rows.add(mutableListOf(b))
            }
            return rows.sortedBy { r -> r.minOf { it.box!!.top } }
                .flatMap { r -> r.sortedBy { it.box!!.left } } + noBox
        }

        /** 两块视为同一行：纵向重叠超过较矮者高度的一半 */
        private fun sameLine(a: Rect, b: Rect): Boolean {
            val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (overlap <= 0) return false
            return overlap.toFloat() >= minOf(a.height(), b.height()) * 0.5f
        }
    }
}
