package com.examhelper.shua.data.export

import com.examhelper.shua.BuildConfig
import com.examhelper.shua.data.db.APP_DATABASE_VERSION
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.ShuaBankFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** `.shua` 本地学习包：manifest + 题库 JSON + 学习记录 JSON，外部为 zip 容器。 */
object ShuaArchive {
    const val EXTENSION = ".shua"
    const val MANIFEST = "manifest.json"
    const val RECORDS = "learning-records.json"
    private const val BANKS_DIR = "banks/"

    data class Entry(val name: String, val bytes: ByteArray)
    private data class PreparedBank(
        val bank: ShuaBankFile,
        val fileName: String,
        val bytes: ByteArray,
        val sha256: String
    )

    fun suggestedFileName(stamp: String): String = "shualegeti-$stamp$EXTENSION"

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
        val prepared = banks.mapIndexed { i, (bank, bytes) ->
            val fileName = "$BANKS_DIR${(bank.bankId ?: i.toLong()).toString().padStart(6, '0')}-${safeFile(bank.name)}.json"
            PreparedBank(bank, fileName, bytes, sha256(bytes))
        }
        val out = ByteArrayOutputStream()
        write(
            listOf(Entry(MANIFEST, buildManifest(exportedAt, prepared))) +
                prepared.map { Entry(it.fileName, it.bytes) } +
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

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun buildManifest(exportedAt: Long, banks: List<PreparedBank>): ByteArray {
        val obj = buildJsonObject {
            put("schema", "shualegeti.shua.v2")
            put("appVersion", BuildConfig.VERSION_NAME)
            put("appVersionCode", BuildConfig.VERSION_CODE)
            put("databaseVersion", APP_DATABASE_VERSION)
            put("exportedAt", exportedAt)
            put("exportedAtText", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(exportedAt)))
            put("bankCount", banks.size)
            put("questionCount", banks.sumOf { it.bank.questions.size })
            put("banks", buildJsonArray {
                banks.forEach { p ->
                    val b = p.bank
                    add(buildJsonObject {
                        b.bankId?.let { put("bankId", it) }
                        b.groupId?.let { put("groupId", it) }
                        b.groupName?.let { put("groupName", it) }
                        put("isGroup", b.isGroup)
                        put("name", b.name)
                        put("questionCount", b.questions.size)
                        put("sourceFileName", b.sourceFileName)
                        put("file", p.fileName)
                        put("sha256", p.sha256)
                        put("mergedFrom", buildJsonArray { b.mergedFrom.forEach { add(it) } })
                    })
                }
            })
        }
        return JsonCodec.json.encodeToString(JsonObject.serializer(), obj).toByteArray(Charsets.UTF_8)
    }

    private fun safeFile(name: String): String = name
        .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
        .trim('_')
        .take(60)
        .ifBlank { "bank" }
}
