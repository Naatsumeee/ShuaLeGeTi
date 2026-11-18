package com.examhelper.shua

import com.examhelper.shua.data.parse.ChunkWorkResult
import com.examhelper.shua.data.parse.runChunkScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ChunkParseSchedulerTest {

    @Test
    fun warmupRunsAloneThenPoolStaysFull() = runBlocking {
        val events = mutableListOf<String>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        runChunkScheduler(
            indices = listOf(0, 1, 2, 3, 4, 5),
            concurrency = 3,
            warmupRequired = true,
            parse = { index ->
                val now = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, now) }
                events += "start$index"
                delay(if (index == 0) 50 else 10)
                active.decrementAndGet()
                ChunkWorkResult(index, emptyList())
            },
            onResult = { events += "done${it.index}" }
        )
        assertEquals("start0", events.first())
        assertEquals("done0", events[1])
        assertEquals(3, maxActive.get())
    }

    @Test
    fun fastWorkerConsumesNextChunkWithoutWaitingForSlowWorker() = runBlocking {
        val starts = mutableListOf<Int>()
        val slowStillRunning = AtomicBoolean(false)
        runChunkScheduler(
            indices = listOf(1, 2, 3),
            concurrency = 2,
            warmupRequired = false,
            parse = { index ->
                starts += index
                if (index == 1) slowStillRunning.set(true)
                if (index == 3) assertTrue(slowStillRunning.get())
                delay(if (index == 1) 200 else 10)
                if (index == 1) slowStillRunning.set(false)
                ChunkWorkResult(index, emptyList())
            },
            onResult = {}
        )
        assertEquals(listOf(1, 2, 3), starts)
    }

    @Test
    fun cancellationReachesPendingWorkersAndSchedulerReturns() = runBlocking {
        val parseStarted = CompletableDeferred<Unit>()
        var cleanupAfterCancel = false
        val scheduler = launch(Dispatchers.Default) {
            runChunkScheduler(
                indices = listOf(0, 1),
                concurrency = 1,
                warmupRequired = false,
                parse = {
                    parseStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cleanupAfterCancel = true
                    }
                    ChunkWorkResult(it, emptyList())
                },
                onResult = {}
            )
        }
        parseStarted.await()
        scheduler.cancelAndJoin()
        assertTrue(cleanupAfterCancel)
    }

    @Test
    fun parseCancellationPropagatesToWholeScheduler() = runBlocking {
        val failure = runCatching {
            withTimeout(250) {
                runChunkScheduler(
                    indices = listOf(0, 1),
                    concurrency = 2,
                    warmupRequired = false,
                    parse = { index ->
                        if (index == 0) withTimeout(1) { awaitCancellation() }
                        awaitCancellation()
                    },
                    onResult = {}
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals("Timed out waiting for 1 ms", failure?.message)
    }
}
