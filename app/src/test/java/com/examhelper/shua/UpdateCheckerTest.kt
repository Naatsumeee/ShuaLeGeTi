package com.examhelper.shua

import com.examhelper.shua.data.update.UpdateChecker
import com.examhelper.shua.data.update.UpdateCheckException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `语义版本比较只把更高版本标为更新`() {
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.2.0"))
        assertTrue(UpdateChecker.isNewer("1.1.0", "v1.1.1"))
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.1.9"))
    }

    @Test
    fun `本机版本名或构建号不低于 release 时不提示更新`() {
        assertFalse(UpdateChecker.isUpdateAvailable("1.1.0", 1086, "1.1.0", 15))
        assertFalse(UpdateChecker.isUpdateAvailable("1.2.0", 20, "1.1.0", 999))
        assertTrue(UpdateChecker.isUpdateAvailable("1.1.0", 14, "1.1.0", 15))
        assertTrue(UpdateChecker.isUpdateAvailable("1.1.0", 1086, "1.2.0", 1100))
    }

    @Test
    fun `从 release 说明解析 versionCode`() {
        assertEquals(15, UpdateChecker.parseVersionCode("更新内容\nversionCode: 15"))
        assertEquals(1100, UpdateChecker.parseVersionCode("versionCode：1100"))
        assertEquals(null, UpdateChecker.parseVersionCode("没有构建号"))
    }

    @Test
    fun `网络失败提示包含 HTTP 状态或无状态说明`() {
        assertTrue(
            UpdateChecker.errorMessage(UpdateCheckException(503, "server"))
                .contains("HTTP 503")
        )
        assertTrue(
            UpdateChecker.errorMessage(IllegalStateException("timeout"))
                .contains("无 HTTP 状态码")
        )
        assertTrue(
            UpdateChecker.errorMessage(
                UpdateCheckException(
                    403,
                    "all failed",
                    "Gitee HTTP 404；GitHub API HTTP 403"
                )
            ).contains("Gitee HTTP 404；GitHub API HTTP 403")
        )
    }

    @Test
    fun `静态 update manifest 可解析并保留备用下载`() {
        val info = UpdateChecker.parseManifest(
            """
            {
              "schema": 1,
              "versionName": "1.3.0",
              "versionCode": 1200,
              "notes": ["新增 A", "修复 B"],
              "downloads": [
                "https://gitee.com/Naatsumeee/ShuaLeGeTi-release/attach_files/1/download/app.apk",
                "https://github.com/Naatsumeee/ShuaLeGeTi/releases/download/v1.3.0/app.apk"
              ],
              "releaseUrl": "https://github.com/Naatsumeee/ShuaLeGeTi/releases/tag/v1.3.0"
            }
            """.trimIndent()
        )
        assertEquals("1.3.0", info.version)
        assertEquals(1200, info.versionCode)
        assertTrue(info.notes.contains("新增 A"))
        assertTrue(info.downloadUrl.startsWith("https://gitee.com/"))
        assertTrue(info.fallbackDownloadUrl!!.startsWith("https://github.com/"))
    }

    @Test
    fun `静态 manifest 拒绝非信任下载域名`() {
        val error = runCatching {
            UpdateChecker.parseManifest(
                """
                {
                  "versionName": "1.3.0",
                  "versionCode": 1200,
                  "downloads": ["https://evil.example/app.apk"]
                }
                """.trimIndent()
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `下载地址只接受 https 和官方域名`() {
        assertTrue(UpdateChecker.trustedDownloadUrl("https://github.com/a/b") != null)
        assertTrue(UpdateChecker.trustedDownloadUrl("https://gitee.com/a/b") != null)
        assertEquals(null, UpdateChecker.trustedDownloadUrl("http://github.com/a/b"))
        assertEquals(null, UpdateChecker.trustedDownloadUrl("https://github.com.evil.example/a"))
    }
}
