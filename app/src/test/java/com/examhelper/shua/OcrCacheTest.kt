package com.examhelper.shua

import com.examhelper.shua.data.storage.OcrCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OcrCacheTest {
    private val context = RuntimeEnvironment.getApplication()
    private val fingerprint = OcrCache.fingerprint("same-file".toByteArray())

    @Before
    fun cleanBefore() {
        com.examhelper.shua.data.storage.AppStorage.ocrCacheDir(context).deleteRecursively()
    }

    @After
    fun cleanAfter() {
        com.examhelper.shua.data.storage.AppStorage.ocrCacheDir(context).deleteRecursively()
    }

    @Test
    fun cacheRoundTripsAndIsIsolatedByEngine() {
        OcrCache.write(context, fingerprint, "rapidocr", "第一题\n第二题")

        assertEquals("第一题\n第二题", OcrCache.read(context, fingerprint, "rapidocr"))
        assertNotNull(OcrCache.find(context, fingerprint, "rapidocr"))
        assertNull(OcrCache.find(context, fingerprint, "mlkit"))
    }

    @Test
    fun fingerprintIsStableForSameBytes() {
        assertEquals(fingerprint, OcrCache.fingerprint("same-file".toByteArray()))
        assertNull(OcrCache.find(context, "missing", "rapidocr"))
    }
}
