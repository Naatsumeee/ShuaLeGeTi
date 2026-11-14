package com.examhelper.shua

import com.examhelper.shua.data.update.UpdateChecker
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
}
