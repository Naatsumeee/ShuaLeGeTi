package com.examhelper.shua.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val versionCode: Int?,
    val releaseUrl: String,
    val downloadUrl: String,
    val notes: String,
    val fallbackDownloadUrl: String? = null
)

class UpdateCheckException(
    val statusCode: Int?,
    message: String,
    /** 多源检查时给用户看的逐源摘要，如 `Gitee HTTP 404；jsDelivr 超时`。 */
    val sourceSummary: String? = null
) : Exception(message)

internal data class UpdateSource(val name: String, val url: String)

object UpdateChecker {
    private const val GITHUB_LATEST_URL =
        "https://api.github.com/repos/Naatsumeee/ShuaLeGeTi/releases/latest"
    private const val GITEE_MANIFEST_URL =
        "https://gitee.com/Naatsumeee/ShuaLeGeTi-release/raw/master/update.json"
    private const val JSDELIVR_MANIFEST_URL =
        "https://cdn.jsdelivr.net/gh/Naatsumeee/ShuaLeGeTi@main/update.json"
    private const val GITHUB_RAW_MANIFEST_URL =
        "https://raw.githubusercontent.com/Naatsumeee/ShuaLeGeTi/main/update.json"

    internal val MANIFEST_SOURCES = listOf(
        UpdateSource("Gitee", GITEE_MANIFEST_URL),
        UpdateSource("jsDelivr", JSDELIVR_MANIFEST_URL),
        UpdateSource("GitHub Raw", GITHUB_RAW_MANIFEST_URL)
    )

    private val allowedDownloadHosts = setOf(
        "gitee.com",
        "github.com",
        "cdn.jsdelivr.net",
        "raw.githubusercontent.com"
    )
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    fun isNewer(current: String, latest: String): Boolean =
        compareVersions(current, latest) < 0

    fun isUpdateAvailable(
        currentVersion: String,
        currentCode: Int,
        latestVersion: String,
        latestCode: Int?
    ): Boolean {
        val nameComparison = compareVersions(currentVersion, latestVersion)
        if (nameComparison > 0) return false
        if (latestCode != null && currentCode >= latestCode) return false
        return nameComparison < 0 || latestCode != null
    }

    fun parseVersionCode(notes: String): Int? =
        Regex("""(?i)versionCode\s*[:：]\s*(\d+)""").find(notes)
            ?.groupValues?.get(1)?.toIntOrNull()

    fun errorMessage(error: Throwable): String {
        val checkError = error as? UpdateCheckException
        val detail = checkError?.sourceSummary?.takeIf { it.isNotBlank() }
        val suffix = if (detail != null) {
            "（$detail）"
        } else {
            checkError?.statusCode?.let { "（HTTP $it）" }
                ?: "（无 HTTP 状态码，连接失败或超时）"
        }
        return "网络问题导致查询失败$suffix，请前往作者 @Natsume 抖音主页获取最新版本信息"
    }

    fun latest(): UpdateInfo {
        val failures = mutableListOf<String>()
        for (source in MANIFEST_SOURCES) {
            try {
                return fetchManifest(source)
            } catch (error: Throwable) {
                failures += sourceFailure(source.name, error)
            }
        }
        try {
            return fetchGitHubApi()
        } catch (error: Throwable) {
            failures += sourceFailure("GitHub API", error)
        }
        throw UpdateCheckException(
            statusCode = null,
            message = "所有更新源均查询失败",
            sourceSummary = failures.joinToString("；")
        )
    }

    internal fun parseManifest(raw: String): UpdateInfo {
        val obj = json.parseToJsonElement(raw).jsonObject
        val schema = obj["schema"]?.jsonPrimitive?.intOrNull ?: 1
        require(schema == 1) { "不支持的 update.json schema: $schema" }

        val version = (
            obj["versionName"]?.jsonPrimitive?.contentOrNull
                ?: obj["version"]?.jsonPrimitive?.contentOrNull
            )?.trim()?.removePrefix("v")?.takeIf { it.isNotBlank() }
            ?: error("update.json 缺少 versionName")
        val versionCode = obj["versionCode"]?.jsonPrimitive?.intOrNull
            ?.takeIf { it > 0 }
            ?: error("update.json 缺少有效 versionCode")

        val downloads = when (val value = obj["downloads"] ?: obj["downloadUrls"]) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> listOfNotNull(obj["downloadUrl"]?.jsonPrimitive?.contentOrNull)
        }.mapNotNull(::trustedDownloadUrl).distinct()
        require(downloads.isNotEmpty()) { "update.json 没有可信下载地址" }

        val notes = when (val value = obj["notes"] ?: obj["changelog"]) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString("\n")
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            else -> ""
        }.trim().take(1200)
        val releaseUrl = trustedDownloadUrl(
            obj["releaseUrl"]?.jsonPrimitive?.contentOrNull
        ) ?: downloads.first()

        return UpdateInfo(
            version = version,
            versionCode = versionCode,
            releaseUrl = releaseUrl,
            downloadUrl = downloads.first(),
            notes = notes,
            fallbackDownloadUrl = downloads.getOrNull(1)
        )
    }

    internal fun trustedDownloadUrl(raw: String?): String? {
        val url = raw?.trim()?.takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.host !in allowedDownloadHosts) return null
        return url.toString()
    }

    private fun fetchManifest(source: UpdateSource): UpdateInfo {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "ShuaGeLeTi-Android")
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateCheckException(response.code, "${source.name} HTTP ${response.code}")
            }
            return parseManifest(response.body?.string().orEmpty())
        }
    }

    private fun fetchGitHubApi(): UpdateInfo {
        val request = Request.Builder()
            .url(GITHUB_LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ShuaGeLeTi-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateCheckException(response.code, "GitHub HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val obj = json.parseToJsonElement(body).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.content.orEmpty()
            val releaseUrl = obj["html_url"]?.jsonPrimitive?.content.orEmpty()
            val notes = obj["body"]?.jsonPrimitive?.content.orEmpty()
            val apk = obj["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk", true) == true }
                ?.get("browser_download_url")?.jsonPrimitive?.content
            return UpdateInfo(
                version = tag.removePrefix("v"),
                versionCode = parseVersionCode(notes),
                releaseUrl = releaseUrl,
                downloadUrl = apk ?: releaseUrl,
                notes = notes.take(1200)
            )
        }
    }

    private fun sourceFailure(name: String, error: Throwable): String {
        val status = (error as? UpdateCheckException)?.statusCode
        return if (status != null) {
            "$name HTTP $status"
        } else {
            val reason = when (error) {
                is java.net.SocketTimeoutException,
                is java.io.InterruptedIOException -> "超时"
                else -> error.message?.take(60) ?: "连接失败"
            }
            "$name $reason"
        }
    }

    private fun compareVersions(current: String, latest: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(current)
        val b = parts(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val delta = (a.getOrElse(i) { 0 }) - (b.getOrElse(i) { 0 })
            if (delta != 0) return delta
        }
        return 0
    }
}
