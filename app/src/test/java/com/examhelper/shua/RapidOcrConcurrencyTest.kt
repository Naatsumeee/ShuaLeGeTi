package com.examhelper.shua

import com.examhelper.shua.data.ingest.OrderedPageAccumulator
import com.examhelper.shua.data.ocr.RapidOcrConcurrency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RapidOcrConcurrencyTest {

    @Test
    fun `concurrency is clamped to experimental one through four range`() {
        assertEquals(1, RapidOcrConcurrency.clamp(0))
        assertEquals(1, RapidOcrConcurrency.clamp(1))
        assertEquals(4, RapidOcrConcurrency.clamp(8))
        assertEquals(4, RapidOcrConcurrency.ocrThreadsPerLane(1))
        assertEquals(2, RapidOcrConcurrency.ocrThreadsPerLane(2))
        assertEquals(1, RapidOcrConcurrency.ocrThreadsPerLane(4))
        assertEquals(1, RapidOcrConcurrency.layoutThreadsPerLane(4))
    }

    @Test
    fun `parallel page results wait for gaps and flush in page order`() {
        val accumulator = OrderedPageAccumulator(firstPage = 2, prefix = "[[PG:1]]\nold\n")

        assertNull(accumulator.put(4, "four"))
        assertNull(accumulator.put(3, "three"))
        assertEquals(4, accumulator.put(2, "two"))

        val text = accumulator.snapshot()
        assertTrue(text.indexOf("[[PG:2]]") < text.indexOf("[[PG:3]]"))
        assertTrue(text.indexOf("[[PG:3]]") < text.indexOf("[[PG:4]]"))
    }

    @Test
    fun `duplicate completed page does not duplicate output`() {
        val accumulator = OrderedPageAccumulator(firstPage = 1, prefix = "")
        assertEquals(1, accumulator.put(1, "one"))
        assertEquals(2, accumulator.put(2, "two"))
        assertNull(accumulator.put(1, "one"))
        assertEquals(1, accumulator.snapshot().windowed("one".length).count { it == "one" })
    }
}
