package com.benjaminwan.ocrlibrary

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap

class OcrEngine(context: Context) {
    companion object {
        const val numThread: Int = 4

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

    init {
        System.loadLibrary("RapidOcr")
        val ret = init(
            context.assets, numThread,
            DET_MODEL,
            CLS_MODEL,
            REC_MODEL,
            KEYS_FILE
        )
        if (!ret) throw IllegalArgumentException()
    }

    var padding: Int = 50
    var boxScoreThresh: Float = 0.5f
    var boxThresh: Float = 0.3f
    var unClipRatio: Float = 1.6f
    var doAngle: Boolean = true
    var mostAngle: Boolean = true

    fun detect(input: Bitmap, output: Bitmap, maxSideLen: Int) =
        detect(
            input, output, padding, maxSideLen,
            boxScoreThresh, boxThresh,
            unClipRatio, doAngle, mostAngle
        )

    external fun init(
        assetManager: AssetManager,
        numThread: Int, detName: String,
        clsName: String, recName: String, keysName: String
    ): Boolean

    external fun detect(
        input: Bitmap, output: Bitmap, padding: Int, maxSideLen: Int,
        boxScoreThresh: Float, boxThresh: Float,
        unClipRatio: Float, doAngle: Boolean, mostAngle: Boolean
    ): OcrResult

    external fun benchmark(input: Bitmap, loop: Int): Double

}