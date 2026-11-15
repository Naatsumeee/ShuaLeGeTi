package com.examhelper.shua

import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.llm.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class LlmClientCancellationTest {

    @Test
    fun `cancel interrupts a blocked request promptly`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    Thread.sleep(2_500)
                    val body = """{"choices":[{"message":{"content":"ok"}}]}"""
                    socket.getOutputStream().bufferedWriter().use { output ->
                        output.write("HTTP/1.1 200 OK\r\n")
                        output.write("Content-Type: application/json\r\n")
                        output.write("Content-Length: ${body.toByteArray().size}\r\n")
                        output.write("Connection: close\r\n\r\n")
                        output.write(body)
                    }
                }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val provider = LlmProvider(
                name = "delay",
                baseUrl = "http://127.0.0.1:${server.localPort}",
                apiKey = "test",
                model = "test"
            )
            val client = LlmClient(
                OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
            )
            val job = launch(Dispatchers.Default) {
                runCatching {
                    client.chat(provider, systemPrompt = "s", userPrompt = "u")
                }
            }
            delay(150)
            val started = System.nanoTime()
            job.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 1_000)
        } finally {
            server.close()
            serverThread.interrupt()
        }
    }
}
