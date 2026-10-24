package com.examhelper.shua.data.llm

import com.examhelper.shua.data.db.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    val stream: Boolean = false
) {
    @Serializable
    data class ResponseFormat(val type: String = "json_object")
}

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList(),
    val error: ApiError? = null
) {
    @Serializable
    data class Choice(val index: Int = 0, val message: ChatMessage? = null)

    @Serializable
    data class ApiError(val message: String? = null, val type: String? = null)
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * OpenAI 兼容 /chat/completions 客户端。
 * 适用于 DeepSeek / GLM / Kimi / Qwen / OpenAI / Gemini-OpenAI端点 / Ollama 等。
 */
class LlmClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun chat(
        provider: LlmProvider,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean = false,
        temperature: Double? = provider.temperature.toDouble()
    ): String = withContext(Dispatchers.IO) {
        runCatching { chatOnce(provider, systemPrompt, userPrompt, jsonMode, temperature) }
            .recoverCatching {
                // 部分兼容端点不支持 response_format，去掉后重试一次
                if (jsonMode && it is LlmException) chatOnce(provider, systemPrompt, userPrompt, false, temperature)
                else throw it
            }
            .getOrThrow()
    }

    private fun chatOnce(
        provider: LlmProvider,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean,
        temperature: Double?
    ): String {
        val body = ChatCompletionRequest(
            model = provider.model,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userPrompt)
            ),
            temperature = temperature,
            responseFormat = if (jsonMode) ChatCompletionRequest.ResponseFormat() else null
        )
        val url = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody(jsonMediaType))
            .build()

        return client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw LlmException("HTTP ${resp.code}: ${text.take(300)}")
            }
            val parsed = runCatching { json.decodeFromString<ChatCompletionResponse>(text) }
                .getOrElse { throw LlmException("响应解析失败: ${text.take(200)}", it) }
            parsed.error?.let { throw LlmException(it.message ?: "API 错误") }
            parsed.choices.firstOrNull()?.message?.content
                ?: throw LlmException("模型未返回内容")
        }
    }

    /** 测试连通性：发一句 "ping" 要求回复 pong */
    suspend fun testConnection(provider: LlmProvider): Result<String> = runCatching {
        chat(
            provider,
            systemPrompt = "You are a connectivity test. Reply with exactly: PONG",
            userPrompt = "ping",
            temperature = 0.0
        ).trim().take(50)
    }
}
