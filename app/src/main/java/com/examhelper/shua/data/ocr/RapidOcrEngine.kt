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

    /**
     * 引擎实例进程内共享。
     *
     * 每次 `OcrEngine(...)` 构造都会走一遍 JNI init：重载三个 ONNX 模型 + 重建字典。
     * 原来每导入一次题库就新建一个实例，代价是几百毫秒的加载，更严重的是
     * 原生层 `CrnnNet::initModel` 过去不清空 `keys`，重复 init 会让字典翻倍、
     * 识别结果整体错位成乱码（已在 CrnnNet.cpp 修掉，但这里也不再重复 init）。
     */
    private val engine by lazy { sharedEngine(appContext) }

    /**
     * 检测阶段的长边上限。**这是小字漏检的关键**：PDF 页面渲染出来约 1400×1980，
     * 这里若给 1280，A4 扫描件在检测时只剩 905px 高，10pt 的小字字高约 8px，
     * DBNet 很容易判成噪声。给 1920 让小字有足够像素，代价是耗时约 2.25 倍（按面积）。
     */
    private val detectMaxSideLen: Int = 1920

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

            val result = engine.detect(bitmap, output, maxSideLen = detectMaxSideLen)
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

    companion object {
        @Volatile
        private var engineInstance: com.benjaminwan.ocrlibrary.OcrEngine? = null

        /**
         * 进程内唯一的 OCR 引擎。构造一次要重载三个 ONNX 并建字典，
         * 重复构造既慢，也会把原生层的字典反复追加（旧 bug）。
         */
        @Synchronized
        private fun sharedEngine(context: Context): com.benjaminwan.ocrlibrary.OcrEngine {
            engineInstance?.let { return it }
            return com.benjaminwan.ocrlibrary.OcrEngine(context.applicationContext).apply {
                // 扫描试卷场景参数。
                //
                // boxThresh / unClipRatio 的取值依据（官方参数说明）：
                // · box_thresh「存在漏检时，调低该值」——试卷里 A~E 五个选项常被整块漏掉，
                //   调低到 0.2 用少量误检换召回，多认几个噪声字比漏掉整个选项好；
                // · unclip_ratio「出现框截断文字的情况，调大该值」——1.6 → 1.8 让文本框
                //   多留一点边，避免紧贴的文字被切掉首尾。
                // boxScoreThresh 仍是 0.5：它管的是「算不算文字」，再降会把污点当字。
                padding = 50
                boxScoreThresh = 0.5f
                boxThresh = 0.2f
                unClipRatio = 1.8f
                doAngle = true
                mostAngle = true
            }.also { engineInstance = it }
        }
    }
}
