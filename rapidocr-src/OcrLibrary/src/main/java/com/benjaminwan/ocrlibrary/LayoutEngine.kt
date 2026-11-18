package com.benjaminwan.ocrlibrary

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import java.io.Closeable

/**
 * PP-DocLayout-S 版面分析（可选能力）。
 *
 * 它给出的是「段落级区域」——正文/标题/表格/公式/页眉页脚各在哪，
 * 用途是给 OCR 结果算栏边界、剔除页眉页脚、标记表格与公式，
 * 而不是按框裁剪后重新识别。
 */
class LayoutEngine(context: Context, threads: Int = DEFAULT_THREADS) : Closeable {

    companion object {
        const val DEFAULT_THREADS: Int = 2

        /** PP-DocLayout-S，4.7MB，480x480 固定输入。 */
        private const val MODEL = "pp_doclayout_s.onnx"

        private const val LABEL_COUNT = 23

        /** 与模型输出严格对齐，顺序即 labelId。 */
        private val LABELS = arrayOf(
            "paragraph_title", "image", "text", "number", "abstract", "content",
            "figure_title", "formula", "table", "table_title", "reference", "doc_title",
            "footnote", "header", "algorithm", "footer", "seal", "chart_title",
            "chart", "formula_number", "header_image", "footer_image", "aside_text"
        )
    }

    /** 模型缺失或加载失败时为 false，调用方应降级为「不做版面分析」。 */
    private var handle: Long = 0L
    val available: Boolean
        get() = handle != 0L

    init {
        System.loadLibrary("RapidOcr")
        handle = nativeCreate(context.assets, threads.coerceAtLeast(1), MODEL)
    }

    /**
     * 返回原始 float 行：[labelId, score, x1, y1, x2, y2]，坐标为原图尺度。
     * 未加载模型时返回空列表。
     */
    fun detect(input: Bitmap, scoreThresh: Float = 0.3f): List<FloatArray> {
        if (handle == 0L) return emptyList()
        return nativeDetect(handle, input, scoreThresh)?.toList() ?: emptyList()
    }

    /** labelId 对应的类别名；越界按 text 处理。 */
    fun labelName(labelId: Int): String =
        LABELS.getOrElse(labelId) { "text" }

    private external fun nativeCreate(assetManager: AssetManager, numThread: Int, modelName: String): Long
    private external fun nativeDetect(handle: Long, input: Bitmap, scoreThresh: Float): Array<FloatArray>?
    private external fun nativeRelease(handle: Long)

    override fun close() {
        if (handle == 0L) return
        nativeRelease(handle)
        handle = 0L
    }
}
