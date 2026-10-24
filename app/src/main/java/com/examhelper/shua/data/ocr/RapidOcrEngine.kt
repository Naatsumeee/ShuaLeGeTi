package com.examhelper.shua.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RapidOCR（PP-OCRv3 ONNX，离线高精度引擎）。
 * 模型内置 assets（det/cls/rec + 字典），原生库来自 RapidOcrAndroidOnnx AAR。
 */
class RapidOcrEngine(context: Context) : OcrProvider {

    private val appContext = context.applicationContext

    private val engine by lazy {
        com.benjaminwan.ocrlibrary.OcrEngine(appContext).apply {
            // 扫描试卷场景参数
            padding = 50
            boxScoreThresh = 0.5f
            boxThresh = 0.3f
            unClipRatio = 1.6f
            doAngle = true
            mostAngle = true
        }
    }

    override suspend fun recognize(bitmap: Bitmap): OcrPageResult = withContext(Dispatchers.Default) {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        try {
            val result = engine.detect(bitmap, output, maxSideLen = 1280)
            val blocks = result.textBlocks.map { tb ->
                val xs = tb.boxPoint.map { it.x }
                val ys = tb.boxPoint.map { it.y }
                OcrBlock(
                    text = tb.text,
                    box = Rect(xs.minOrNull() ?: 0, ys.minOrNull() ?: 0, xs.maxOrNull() ?: 0, ys.maxOrNull() ?: 0)
                )
            }
            OcrPageResult(
                text = MlKitOcrEngine.rebuildReadingOrder(blocks, bitmap.width),
                blocks = blocks,
                pageWidth = bitmap.width,
                pageHeight = bitmap.height
            )
        } finally {
            output.recycle()
        }
    }
}
