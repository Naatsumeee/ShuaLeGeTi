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
    val releaseUrl: String,
    val downloadUrl: String,
    val notes: String
)

object UpdateChecker {
    private const val LATEST_URL = "https://api.github.com/repos/Naatsumeee/ShuaLeGeTi/releases/latest"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun isNewer(current: String, latest: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(current)
        val b = parts(latest)
        for (i in 0 until maxOf(a.size, b.size)) {
            val delta = (b.getOrElse(i) { 0 }) - (a.getOrElse(i) { 0 })
            if (delta != 0) return delta > 0
        }
        return false
    }

    fun latest(): UpdateInfo {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ShuaGeLeTi-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub HTTP ${response.code}")
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
                releaseUrl = releaseUrl,
                downloadUrl = apk ?: releaseUrl,
                notes = notes.take(1200)
            )
        }
    }
}
