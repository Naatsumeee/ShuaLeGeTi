package com.examhelper.shua.data.parse

import com.examhelper.shua.data.model.ParsedLine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

data class ChunkWorkResult(
    val index: Int,
    val lines: List<ParsedLine>?,
    val error: String? = null
)

suspend fun runChunkScheduler(
    indices: List<Int>,
    concurrency: Int,
    warmupRequired: Boolean,
    parse: suspend (Int) -> ChunkWorkResult,
    onResult: suspend (ChunkWorkResult) -> Unit
) = coroutineScope {
    if (warmupRequired && indices.isNotEmpty()) {
        onResult(parse(indices.first()))
    }

    val poolIndices = if (warmupRequired) indices.drop(1) else indices
    if (poolIndices.isEmpty()) return@coroutineScope

    val work = Channel<Int>(Channel.UNLIMITED)
    val results = Channel<ChunkWorkResult>(Channel.UNLIMITED)
    try {
        poolIndices.forEach { work.send(it) }
        work.close()

        repeat(concurrency.coerceAtLeast(1)) {
            launch {
                for (index in work) {
                    try {
                        results.send(parse(index))
                    } catch (e: CancellationException) {
                        // A cancelled worker otherwise looks like normal completion to its parent.
                        results.close(e)
                        throw e
                    }
                }
            }
        }

        repeat(poolIndices.size) {
            onResult(results.receive())
        }
    } finally {
        work.cancel()
        results.cancel()
    }
}
