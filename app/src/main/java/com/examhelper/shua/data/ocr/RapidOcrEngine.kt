package com.examhelper.shua.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.benjaminwan.ocrlibrary.LayoutEngine
import com.examhelper.shua.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RapidOCR（PP-OCRv5 ONNX，离线高精度引擎）。
 * 模型内置 assets（det/cls/rec/版面 + 字典），原生库来自 rapidocr-src/OcrLibrary。
 *
 * 识别流程分两步：
 * 1. 版面分析（PP-DocLayout-S）先看清这页是单栏还是双栏、表格/公式在哪、页眉页脚在哪；
 * 2. 文字识别（PP-OCRv5 det+cls+rec）拿到每个文本块，再按版面结论重排阅读顺序。
 *
 * 版面模型是可选的：assets 里缺文件或加载失败时 [layoutEngineAvailable] 为 false，
 * 自动退回旧的双栏启发式，不影响出题。
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

    /**
     * 版面分析模型。加载失败（assets 没打进模型、NDK 没编译该源文件）时为 null，
     * 由 LayoutEngine.available 告知。
     */
    private val layoutEngine: LayoutEngine? by lazy {
        runCatching { LayoutEngine(appContext).takeIf { it.available } }.getOrNull()
    }

    /** 版面分析模型是否可用（供导入页显示引擎能力） */
    val layoutEngineAvailable: Boolean
        get() = layoutEngine?.available == true

    override suspend fun recognize(bitmap: Bitmap): OcrPageResult = withContext(Dispatchers.Default) {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        try {
            // 第一步：版面分析。给出页眉页脚、表格、公式的位置。
            // 注意栏缝**不**由它决定——段落粒度的框会把双栏并成一整块，
            // 栏缝必须等 OCR 行块出来后再算（见下面 withGutters）。
            val rawLayout = LayoutAnalyzer.analyze(layoutEngine, bitmap)

            val result = engine.detect(bitmap, output, maxSideLen = 1280)
            val blocks = result.textBlocks.map { tb ->
                val xs = tb.boxPoint.map { it.x }
                val ys = tb.boxPoint.map { it.y }
                OcrBlock(
                    text = tb.text,
                    box = Box.of(
                        xs.minOrNull() ?: 0, ys.minOrNull() ?: 0,
                        xs.maxOrNull() ?: 0, ys.maxOrNull() ?: 0
                    )
                )
            }

            // 第二步：用 OCR 行块重算栏缝，覆盖版面模型给的（通常是空的）结论
            val layout = LayoutAnalyzer.withGutters(rawLayout, blocks.mapNotNull { it.box }, bitmap.width)

            if (BuildConfig.DEBUG) {
                Log.d(
                    "ShuaLayout",
                    "page ${bitmap.width}x${bitmap.height} analyzed=${layout.analyzed} " +
                        "cols=${layout.columnCount} cuts=${layout.columnCuts} " +
                        "blocks=${blocks.size} " +
                        "table=${layout.tableBoxes.size} formula=${layout.formulaBoxes.size} " +
                        "noise=${layout.noiseBoxes.size}"
                )
            }

            OcrPageResult(
                text = MlKitOcrEngine.rebuildReadingOrder(blocks, bitmap.width, layout),
                blocks = blocks,
                pageWidth = bitmap.width,
                pageHeight = bitmap.height,
                layout = layout
            )
        } finally {
            output.recycle()
        }
    }
}
