package com.examhelper.shua.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val versionCode: Int?,
    val releaseUrl: String,
    val downloadUrl: String,
    val notes: String
)

class UpdateCheckException(
    val statusCode: Int?,
    message: String
) : Exception(message)

object UpdateChecker {
    private const val LATEST_URL = "https://api.github.com/repos/Naatsumeee/ShuaLeGeTi/releases/latest"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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
        val status = (error as? UpdateCheckException)?.statusCode
        val suffix = status?.let { "（HTTP $it）" }
            ?: "（无 HTTP 状态码，连接失败或超时）"
        return "网络问题导致查询失败$suffix，请前往作者 @Natsume 抖音主页获取最新版本信息"
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

    fun latest(): UpdateInfo {
        val request = Request.Builder()
            .url(LATEST_URL)
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
}
