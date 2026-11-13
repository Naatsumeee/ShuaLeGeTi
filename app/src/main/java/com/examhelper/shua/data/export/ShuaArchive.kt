package com.examhelper.shua.data.export

import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.ShuaBankFile
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * `.shua` 本地学习包：标准 zip 容器，扩展名不是 zip。
 *
 * 设计目标不是发明一套私有格式，而是让「导出的题库 + 学习记录」成为一个
 * 可直接分发的单文件。内部全部是 UTF-8 JSON 与 `manifest.json`，以后即使
 * 换实现也能读；扩展名只是为了让文件管理器里看起来像一个应用文件。
 */
object ShuaArchive {
    const val EXTENSION = ".shua"
    const val MANIFEST = "manifest.json"
    const val RECORDS = "learning-records.json"
    private const val BANKS_DIR = "banks/"

    data class Entry(val name: String, val bytes: ByteArray)

    fun suggestedFileName(stamp: String): String = "shualegeti-$stamp$EXTENSION"

    /** 写成 zip；默认 DEFLATED，题库文本压缩率很高。 */
    fun write(entries: List<Entry>, out: java.io.OutputStream) {
        ZipOutputStream(out).use { zip ->
            entries.forEach { e ->
                zip.putNextEntry(ZipEntry(e.name))
                zip.write(e.bytes)
                zip.closeEntry()
            }
        }
    }

    fun build(
        exportedAt: Long,
        banks: List<Pair<ShuaBankFile, ByteArray>>,
        learningRecordsJson: ByteArray
    ): ByteArray {
        val manifest = buildManifest(exportedAt, banks.map { it.first })
        val out = ByteArrayOutputStream()
        write(
            listOf(Entry(MANIFEST, manifest)) +
                banks.mapIndexed { i, (bank, bytes) ->
                    Entry("$BANKS_DIR${i.toString().padStart(3, '0')}-${safeFile(bank.name)}.json", bytes)
                } +
                Entry(RECORDS, learningRecordsJson),
            out
        )
        return out.toByteArray()
    }

    fun read(bytes: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) out[e.name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        return out
    }

    private fun buildManifest(exportedAt: Long, banks: List<ShuaBankFile>): ByteArray {
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("schema", "shualegeti.shua.v1")
            put("exportedAt", exportedAt)
            put("bankCount", banks.size)
            put("questionCount", banks.sumOf { it.questions.size })
            put("banks", kotlinx.serialization.json.buildJsonArray {
                banks.forEach { b ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("name", b.name)
                        put("questionCount", b.questions.size)
                        put("sourceFileName", b.sourceFileName)
                    })
                }
            })
        }
        return JsonCodec.json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(), obj
        ).toByteArray(Charsets.UTF_8)
    }

    private fun safeFile(name: String): String = name
        .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
        .trim('_')
        .take(60)
        .ifBlank { "bank" }
}
