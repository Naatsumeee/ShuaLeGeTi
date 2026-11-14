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
    }
}
