package com.examhelper.shua.data.storage

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用私有文件的统一入口 —— 全工程只在这里拼路径。
 *
 * 规范约定（不要绕开本对象自己 new File(...)）：
 *  1. 任何中间产物、缓存、临时文件，一律写进应用私有目录（[Context.getCacheDir] /
 *     [Context.getFilesDir]），**绝不直接写 /sdcard、/storage/emulated/0 或
 *     Environment.getExternalStorageDirectory()**。
 *  2. 需要交给用户拿走的文件（题库导出、学习记录导出），走系统文件选择器 SAF
 *     （ActivityResultContracts.CreateDocument + contentResolver.openOutputStream），
 *     由用户决定落到哪儿，应用自己不碰公共目录。
 *  3. 需要临时授权给别的应用写的文件（拍照），放 [cameraDir]，并且 FileProvider
 *     （res/xml/file_paths.xml）**只暴露这一个子目录**，不暴露 cacheDir 全量。
 *  4. 每个用途一个子目录，不往 cacheDir 根目录堆文件，便于按目录整体清理与排查。
 *
 * 目录布局：
 * ```
 * cacheDir/
 *   import/        导入任务的中间产物（OCR 原文、分块 JSONL），支持断点续跑
 *   temp/          一次性临时文件（PDF 落地、OCR 中间图），用完即删
 *   temp/camera/   相机输出（唯一对 FileProvider 暴露的目录）
 *   sfx/           合成音效 wav
 * ```
 */
object AppStorage {

    private const val DIR_IMPORT = "import"
    private const val DIR_TEMP = "temp"
    private const val DIR_CAMERA = "camera"
    private const val DIR_SFX = "sfx"

    /** 导入任务中间产物的保留期：超过则视为废弃任务，启动时清理 */
    private val IMPORT_RETENTION_MS = TimeUnit.DAYS.toMillis(7)

    /** 一次性临时文件的保留期（留出余量，避免误删正在运行的任务刚写入的文件） */
    private val TEMP_RETENTION_MS = TimeUnit.HOURS.toMillis(2)

    /** 历史版本堆在 cacheDir 根目录的文件名前缀，仅用于兜底清理，新代码不再产生 */
    private val LEGACY_PREFIXES = listOf(
        "import_raw_", "import_parsed_", "ingest_", "camera_", "sfx_"
    )

    // ---------- 目录 ----------

    /** 导入流水线的中间产物目录（可安全续跑，不要随手清空） */
    fun importDir(context: Context): File = ensureDir(File(context.cacheDir, DIR_IMPORT))

    /** 一次性临时文件目录 */
    fun tempDir(context: Context): File = ensureDir(File(context.cacheDir, DIR_TEMP))

    /** 相机输出目录：唯一允许通过 FileProvider 交给外部应用写入的目录 */
    fun cameraDir(context: Context): File = ensureDir(File(tempDir(context), DIR_CAMERA))

    /** 合成音效目录 */
    fun sfxDir(context: Context): File = ensureDir(File(context.cacheDir, DIR_SFX))

    // ---------- 具体文件 ----------

    /** 导入任务的 OCR 原文（断点续跑依据：已落盘即不再重复 OCR） */
    fun importRawFile(context: Context, jobId: String): File =
        File(importDir(context), "raw_$jobId.txt")

    /** 导入任务的逐题 JSONL（断点续跑依据：已解析分块不重复请求） */
    fun importParsedFile(context: Context, jobId: String): File =
        File(importDir(context), "parsed_$jobId.jsonl")

    /** PDF 落地文件：用完必须删除，命名带上时间戳避免并发覆盖 */
    fun newIngestPdf(context: Context): File =
        File(tempDir(context), "ingest_${System.currentTimeMillis()}_${counter()}.pdf")

    /** 相机拍照的输出文件 */
    fun newCameraPhoto(context: Context): File =
        File(cameraDir(context), "shot_${System.currentTimeMillis()}_${counter()}.jpg")

    /** 某种合成音效的 wav 文件 */
    fun sfxFile(context: Context, name: String): File =
        File(sfxDir(context), "$name.wav")

    // ---------- 清理 ----------

    /**
     * 启动时清理废弃缓存。只删过期的，不碰新鲜文件，避免干掉正在运行的后台任务。
     * 成本很低（一次 listFiles），在 Application.onCreate 里调用即可。
     */
    fun cleanupStale(context: Context) {
        val now = System.currentTimeMillis()
        // temp 目录含 camera 子目录，递归清理（CameraX/相机可能留下 0 字节废文件）
        runCatching { purge(tempDir(context), now - TEMP_RETENTION_MS, recursive = true) }
        runCatching { purge(importDir(context), now - IMPORT_RETENTION_MS) }
        runCatching { purge(sfxDir(context), now - IMPORT_RETENTION_MS) }
        // 旧版本曾把中间文件直接堆在 cacheDir 根目录（import_raw_* / import_parsed_* /
        // ingest_*.pdf / camera_*.jpg / sfx_*.wav），新代码已不再读取这些路径，过期即删。
        runCatching { purgeLegacyRootFiles(context, now - TEMP_RETENTION_MS) }
    }

    /** 清理历史版本遗留在 cacheDir 根目录的散落文件（保留仍然新鲜的，避免误伤正在跑的老任务） */
    private fun purgeLegacyRootFiles(context: Context, threshold: Long) {
        val root = context.cacheDir ?: return
        root.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < threshold &&
                LEGACY_PREFIXES.any { f.name.startsWith(it) }
            ) {
                f.delete()
            }
        }
    }

    /** 删除导入任务的全部中间产物（任务结束或被用户丢弃时调用） */
    fun deleteImportArtifacts(context: Context, jobId: String) {
        runCatching { importRawFile(context, jobId).delete() }
        runCatching { importParsedFile(context, jobId).delete() }
    }

    // ---------- 内部 ----------

    private fun ensureDir(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun purge(dir: File, threshold: Long, recursive: Boolean = false) {
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                if (recursive) purge(f, threshold, recursive = true)
                if (f.listFiles()?.isEmpty() == true) f.delete()
            } else if (f.lastModified() < threshold) {
                f.delete()
            }
        }
    }

    /** 同一毫秒内多次调用时避免文件名撞车 */
    private fun counter(): Int = (System.nanoTime() % 100_000L).toInt()
}
