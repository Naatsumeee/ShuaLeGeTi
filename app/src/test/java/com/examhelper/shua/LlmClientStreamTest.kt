package com.examhelper.shua

import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.llm.LlmClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class LlmClientStreamTest {

    @Test
    fun `sse emits every content delta instead of only the first`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    val body = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"questions\\\":\"}}]}\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"[]}\"},\"finish_reason\":\"stop\"}]}\n" +
                        "data: [DONE]\n"
                    socket.getOutputStream().bufferedWriter().use { output ->
                        output.write("HTTP/1.1 200 OK\r\n")
                        output.write("Content-Type: text/event-stream\r\n")
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
            val deltas = mutableListOf<String>()
            val result = LlmClient().chatStream(
                provider = LlmProvider(
                    name = "test",
                    baseUrl = "http://127.0.0.1:${server.localPort}",
                    apiKey = "test",
                    model = "test"
                ),
                systemPrompt = "s",
                userPrompt = "u",
                onDelta = deltas::add
            )
            assertEquals(listOf("{\"questions\":", "[]}"), deltas)
            assertEquals("{\"questions\":[]}", result.content)
        } finally {
            server.close()
            serverThread.interrupt()
        }
    }

    @Test
    fun `sse keeps working when keepalive and event precede data`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) Unit
                    val body = ": keep-alive\n" +
                        "event: message\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n" +
                        "data: [DONE]\n"
                    socket.getOutputStream().bufferedWriter().use { output ->
                        output.write("HTTP/1.1 200 OK\r\n")
                        output.write("Content-Type: text/event-stream\r\n")
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
            val result = LlmClient().chatStream(
                provider = LlmProvider(
                    name = "test",
                    baseUrl = "http://127.0.0.1:${server.localPort}",
                    apiKey = "test",
                    model = "test"
                ),
                systemPrompt = "s",
                userPrompt = "u"
            )
            assertEquals("ok", result.content)
        } finally {
            server.close()
            serverThread.interrupt()
        }
    }
}
