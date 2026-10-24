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
                val header = spanning.sortedBy { it.box?.top ?: 0 }.joinToString("\n") { it.text }
                val leftText = left.sortedWith(compareBy({ it.box!!.top }, { it.box!!.left })).joinToString("\n") { it.text }
                val rightText = right.sortedWith(compareBy({ it.box!!.top }, { it.box!!.left })).joinToString("\n") { it.text }
                listOf(header, leftText, rightText).filter { it.isNotBlank() }.joinToString("\n")
            } else {
                blocks.sortedWith(
                    compareBy({ it.box?.top ?: 0 }, { it.box?.left ?: 0 })
                ).joinToString("\n") { it.text }
            }
        }
    }
}
