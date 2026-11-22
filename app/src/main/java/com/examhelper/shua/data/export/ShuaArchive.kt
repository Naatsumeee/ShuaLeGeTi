package com.examhelper.shua.data.export

import com.examhelper.shua.BuildConfig
import com.examhelper.shua.data.db.APP_DATABASE_VERSION
import com.examhelper.shua.data.model.JsonCodec
import com.examhelper.shua.data.model.ShuaBankFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
    data class ValidatedArchive(
        val schema: String,
        val exportedAt: Long,
        val exportedAtText: String,
        val banks: List<ShuaBankFile>,
        val records: JsonObject,
        val questionCount: Int,
        val attemptCount: Int,
        val wrongCount: Int,
        val favoriteCount: Int,
        val killCount: Int
    )
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

    /**
     * 校验 `.shua` 备份包完整性与结构合法性：
     * 1. ZIP 容器可读且非空；
     * 2. 包含合法 `manifest.json`（schema 为 `shualegeti.shua.v1/v2`）；
     * 3. 清单中声明的每个题库 JSON 文件均存在、SHA256 校验通过、可解码为 [ShuaBankFile]；
     * 4. 包含合法 `learning-records.json`（schema 为 `shualegeti.export.*`）。
     */
    fun validate(bytes: ByteArray): ValidatedArchive {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            throw IllegalArgumentException("所选文件不是有效的 .shua 备份包")
        }
        val entries = runCatching { read(bytes) }
            .getOrElse { throw IllegalArgumentException("备份包已损坏，无法解压读取") }
        if (entries.isEmpty()) {
            throw IllegalArgumentException("备份包内容为空")
        }

        val manifestBytes = entries[MANIFEST]
            ?: throw IllegalArgumentException("备份包缺少清单文件（manifest.json）")
        val manifest = runCatching {
            JsonCodec.json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject
        }.getOrElse { throw IllegalArgumentException("备份包清单格式损坏") }

        val schema = manifest["schema"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (schema != "shualegeti.shua.v2" && schema != "shualegeti.shua.v1") {
            throw IllegalArgumentException("不支持的备份版本或非刷了个题备份文件")
        }

        val bankEntries = runCatching { manifest["banks"]?.jsonArray }
            .getOrNull() ?: throw IllegalArgumentException("备份包清单缺少题库列表")

        val decodedBanks = bankEntries.map { elem ->
            val obj = runCatching { elem.jsonObject }
                .getOrElse { throw IllegalArgumentException("备份包清单中的题库条目格式异常") }
            val filePath = obj["file"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("备份包清单缺少题库文件路径")
            val bankBytes = entries[filePath]
                ?: throw IllegalArgumentException("备份包缺少题库文件：$filePath")
            val expectedSha = obj["sha256"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (expectedSha.isNotBlank() && !sha256(bankBytes).equals(expectedSha, ignoreCase = true)) {
                throw IllegalArgumentException("备份包完整性校验失败（题库内容已损坏或被篡改）")
            }
            runCatching {
                JsonCodec.json.decodeFromString(ShuaBankFile.serializer(), bankBytes.toString(Charsets.UTF_8))
            }.getOrElse { throw IllegalArgumentException("题库数据格式损坏：$filePath") }
        }

        val recordsBytes = entries[RECORDS]
            ?: throw IllegalArgumentException("备份包缺少学习记录文件（learning-records.json）")
        val records = runCatching {
            JsonCodec.json.parseToJsonElement(recordsBytes.toString(Charsets.UTF_8)).jsonObject
        }.getOrElse { throw IllegalArgumentException("学习记录文件格式损坏") }

        val recordsSchema = records["schema"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!recordsSchema.startsWith("shualegeti.export.")) {
            throw IllegalArgumentException("学习记录格式不受支持")
        }

        fun requireArray(name: String): JsonArray {
            val el = records[name] ?: return JsonArray(emptyList())
            return runCatching { el.jsonArray }
                .getOrElse { throw IllegalArgumentException("学习记录字段 $name 格式损坏") }
        }

        val attempts = requireArray("attempts")
        val wrongBook = requireArray("wrongBook")
        val favorites = requireArray("favorites")
        val kills = requireArray("kills")

        val exportedAt = manifest["exportedAt"]?.jsonPrimitive?.longOrNull ?: 0L
        val exportedAtText = manifest["exportedAtText"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: if (exportedAt > 0L) {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(exportedAt))
            } else {
                "未知时间"
            }

        return ValidatedArchive(
            schema = schema,
            exportedAt = exportedAt,
            exportedAtText = exportedAtText,
            banks = decodedBanks,
            records = records,
            questionCount = decodedBanks.sumOf { it.questions.size },
            attemptCount = attempts.size,
            wrongCount = wrongBook.size,
            favoriteCount = favorites.size,
            killCount = kills.size
        )
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
