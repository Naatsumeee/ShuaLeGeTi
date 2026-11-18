package com.benjaminwan.ocrlibrary

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import java.io.Closeable

class OcrEngine(context: Context, threads: Int = DEFAULT_THREADS) : Closeable {
    companion object {
        const val DEFAULT_THREADS: Int = 4

        /**
         * 内置模型（PP-OCRv5 mobile）。
         * - det：ch_PP-OCRv5_det_mobile，4.6MB，与 v3 同样的 DB 后处理与 imagenet 归一化
         * - rec：ch_PP-OCRv5_rec_mobile，16MB，输入高 48（与 v3 一致），输出 18385 类
         * - cls：沿用 v2.0 文字方向分类（输入 48x192，与 AngleNet 的 dstWidth/dstHeight 匹配）
         * - 字典：ppocrv5_dict.txt（18383 行 + blank + space = 18385，与 rec 输出严格对齐）
         */
        private const val DET_MODEL = "ch_PP-OCRv5_det_mobile.onnx"
        private const val CLS_MODEL = "ch_ppocr_mobile_v2.0_cls_infer.onnx"
        private const val REC_MODEL = "ch_PP-OCRv5_rec_mobile.onnx"
        private const val KEYS_FILE = "ppocrv5_dict.txt"
    }

    private var handle: Long

    init {
        System.loadLibrary("RapidOcr")
        handle = nativeCreate(
            context.assets, threads.coerceAtLeast(1),
            DET_MODEL,
            CLS_MODEL,
            REC_MODEL,
            KEYS_FILE
        )
        if (handle == 0L) throw IllegalArgumentException("RapidOCR 初始化失败")
    }

    var padding: Int = 50
    var boxScoreThresh: Float = 0.5f
    var boxThresh: Float = 0.3f
    var unClipRatio: Float = 1.6f
    var doAngle: Boolean = true
    var mostAngle: Boolean = true

    fun detect(input: Bitmap, output: Bitmap, maxSideLen: Int): OcrResult {
        check(handle != 0L) { "RapidOCR 已释放" }
        return nativeDetect(
            handle, input, output, padding, maxSideLen,
            boxScoreThresh, boxThresh,
            unClipRatio, doAngle, mostAngle
        )
    }

    private external fun nativeCreate(
        assetManager: AssetManager,
        numThread: Int, detName: String,
        clsName: String, recName: String, keysName: String
    ): Long

    private external fun nativeDetect(
        handle: Long,
        input: Bitmap, output: Bitmap, padding: Int, maxSideLen: Int,
        boxScoreThresh: Float, boxThresh: Float,
        unClipRatio: Float, doAngle: Boolean, mostAngle: Boolean
    ): OcrResult

    private external fun nativeBenchmark(handle: Long, input: Bitmap, loop: Int): Double
    private external fun nativeRelease(handle: Long)

    override fun close() {
        if (handle == 0L) return
        nativeRelease(handle)
        handle = 0L
    }
}
