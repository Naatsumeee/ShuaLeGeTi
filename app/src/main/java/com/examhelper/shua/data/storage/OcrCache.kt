package com.examhelper.shua.data.storage

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

data class CachedOcrSummary(
    val fingerprint: String,
    val engine: String,
    val createdAt: Long
)

/**
 * Source-content keyed OCR cache. The file hash makes a moved/renamed file still
 * hit, while an edited file deliberately misses.
 */
object OcrCache {
    private const val BUFFER_SIZE = 64 * 1024

    fun fingerprint(context: Context, uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        } ?: return null
        digest.digest().toHex()
    }.getOrNull()

    internal fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun find(context: Context, fingerprint: String?, engine: String): CachedOcrSummary? {
        if (fingerprint.isNullOrBlank()) return null
        val file = AppStorage.cachedOcrTextFile(context, fingerprint, engine)
        if (!file.isFile || file.length() == 0L) return null
        return CachedOcrSummary(fingerprint, engine.lowercase(), file.lastModified())
    }

    fun read(context: Context, fingerprint: String, engine: String): String? {
        val file = AppStorage.cachedOcrTextFile(context, fingerprint, engine)
        return runCatching { file.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()
    }

    fun write(context: Context, fingerprint: String, engine: String, text: String) {
        if (fingerprint.isBlank() || text.isBlank()) return
        val target = AppStorage.cachedOcrTextFile(context, fingerprint, engine)
        val temp = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.writeText(text)
                temp.delete()
            }
            target.setLastModified(System.currentTimeMillis())
        }.onFailure {
            temp.delete()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
