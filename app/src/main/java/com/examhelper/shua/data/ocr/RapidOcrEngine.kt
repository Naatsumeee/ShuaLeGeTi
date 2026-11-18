package com.examhelper.shua.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.benjaminwan.ocrlibrary.LayoutEngine
import com.examhelper.shua.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.Closeable

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
class RapidOcrEngine(
    context: Context,
    concurrency: Int = RapidOcrConcurrency.DEFAULT
) : OcrProvider, Closeable {

    private val appContext = context.applicationContext
    override val concurrency: Int = RapidOcrConcurrency.clamp(concurrency)

    private val lock = Any()
    private val workers = mutableListOf<RapidOcrWorker>()
    @Volatile
    private var available: Channel<RapidOcrWorker>? = null
    @Volatile
    private var closed = false

    /** 版面分析模型是否可用（供导入页显示引擎能力） */
    val layoutEngineAvailable: Boolean
        get() = workerSlots().let { slots ->
            workers.firstOrNull()?.layoutEngineAvailable == true
        }

    override suspend fun recognize(bitmap: Bitmap): OcrPageResult {
        val slots = workerSlots()
        val worker = slots.receive()
        return try {
            withContext(Dispatchers.Default) { worker.recognize(bitmap) }
        } finally {
            slots.trySend(worker)
        }
    }

    private fun workerSlots(): Channel<RapidOcrWorker> {
        synchronized(lock) {
            available?.let { return it }
            check(!closed) { "RapidOCR 已释放" }
            val created = mutableListOf<RapidOcrWorker>()
            try {
                repeat(concurrency) {
                    created += RapidOcrWorker(
                        appContext = appContext,
                        ocrThreads = RapidOcrConcurrency.ocrThreadsPerLane(concurrency),
                        layoutThreads = RapidOcrConcurrency.layoutThreadsPerLane(concurrency)
                    )
                }
            } catch (t: Throwable) {
                created.forEach { it.close() }
                throw t
            }
            workers += created
            return Channel<RapidOcrWorker>(created.size).also { channel ->
                created.forEach { channel.trySend(it) }
                available = channel
            }
        }
    }

    override fun close() {
        val slots = synchronized(lock) {
            if (closed) return
            closed = true
            available.also { available = null }
        }
        slots?.close()
        synchronized(lock) {
            workers.forEach { it.close() }
            workers.clear()
        }
    }
}

/**
 * 一路 OCR 的完整原生会话。实例之间不共享 OcrEngine/LayoutEngine，
 * 因为 JNI 侧每个实例都会持有自己的 ONNX Session 和可变工作区。
 */
private class RapidOcrWorker(
    private val appContext: Context,
    private val ocrThreads: Int,
    private val layoutThreads: Int
) : Closeable {
    /**
     * 检测阶段的长边上限。**这是小字漏检的关键**：PDF 页面渲染出来约 1400×1980，
     * 这里若给 1280，A4 扫描件在检测时只剩 905px 高，10pt 的小字字高约 8px，
     * DBNet 很容易判成噪声。给 1920 让小字有足够像素，代价是耗时约 2.25 倍（按面积）。
     */
    private val detectMaxSideLen: Int = 1920

    private val engine = com.benjaminwan.ocrlibrary.OcrEngine(appContext, ocrThreads).apply {
        padding = 50
        boxScoreThresh = 0.5f
        boxThresh = 0.2f
        unClipRatio = 1.8f
        doAngle = true
        mostAngle = true
    }

    private val layoutEngine: LayoutEngine? = runCatching {
        LayoutEngine(appContext, layoutThreads).takeIf { it.available }
    }.getOrNull()

    val layoutEngineAvailable: Boolean get() = layoutEngine != null

    suspend fun recognize(bitmap: Bitmap): OcrPageResult = withContext(Dispatchers.Default) {
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

    override fun close() {
        runCatching { engine.close() }
        runCatching { layoutEngine?.close() }
    }
}

object RapidOcrConcurrency {
    const val DEFAULT = 1
    const val MAX = 4

    fun clamp(value: Int): Int = value.coerceIn(DEFAULT, MAX)
    fun ocrThreadsPerLane(lanes: Int): Int = when (clamp(lanes)) {
        1 -> 4
        2 -> 2
        else -> 1
    }
    fun layoutThreadsPerLane(lanes: Int): Int = if (clamp(lanes) <= 2) 2 else 1
}

/**
 * 多路原生 OCR 如果直接 SIGABRT，Java finally 来不及执行。这里在进程内记下
 * 活跃进程 PID；下一次新进程看到旧 PID 就知道上次并行识别没有正常收尾。
 */
object RapidOcrRuntimeGuard {
    private const val PREFS = "rapid_ocr_runtime"
    private const val KEY_ACTIVE_PID = "active_pid"
    private const val KEY_FAILED = "parallel_failed"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markStarted(context: Context) {
        prefs(context).edit()
            .putInt(KEY_ACTIVE_PID, android.os.Process.myPid())
            .commit()
    }

    fun markFinished(context: Context, success: Boolean) {
        prefs(context).edit()
            .remove(KEY_ACTIVE_PID)
            .putBoolean(KEY_FAILED, prefs(context).getBoolean(KEY_FAILED, false) || !success)
            .apply()
    }

    fun consumeInterrupted(context: Context): Boolean {
        val p = prefs(context)
        val activePid = p.getInt(KEY_ACTIVE_PID, 0)
        if (activePid != 0 && activePid != android.os.Process.myPid()) {
            p.edit().remove(KEY_ACTIVE_PID).putBoolean(KEY_FAILED, true).apply()
        }
        return p.getBoolean(KEY_FAILED, false)
    }

    fun clearFailure(context: Context) {
        prefs(context).edit().remove(KEY_FAILED).apply()
    }
}
