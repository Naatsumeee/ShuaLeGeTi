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
data class ChatMessage(
    val role: String,
    val content: String,
    /**
     * 推理型模型（DeepSeek reasoner / v4-pro 等）的思维链字段。
     * 只用于**读取**响应：若 content 为空而它有内容，说明模型的输出预算被思考耗尽，
     * 这是「深度解题概率性没有结果」的典型成因之一，必须能分辨出来而不是报「未返回内容」。
     */
    @SerialName("reasoning_content") val reasoningContent: String? = null
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false
) {
    @Serializable
    data class ResponseFormat(val type: String = "json_object")
}

/** 计费用量（DeepSeek 原生字段 + OpenAI 兼容 prompt_tokens_details 回退） */
@Serializable
data class ChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("prompt_cache_hit_tokens") val cacheHitTokens: Int = 0,
    @SerialName("prompt_cache_miss_tokens") val cacheMissTokens: Int = 0,
    @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null
) {
    @Serializable
    data class PromptTokensDetails(@SerialName("cached_tokens") val cachedTokens: Int = 0)

    /** 归一化命中数：DeepSeek 原生字段优先，缺失时回退 OpenAI 的 cached_tokens */
    fun normalizedCacheHit(): Int =
        if (cacheHitTokens > 0) cacheHitTokens else (promptTokensDetails?.cachedTokens ?: 0)
}

class ChatResult(
    val content: String,
    val usage: ChatUsage,
    /**
     * 结束原因。`length` 表示被 max_tokens 截断 —— 深度解题这类长回答必须能识别出来：
     * 截断的解析看起来"有内容"，但会被当作完整结果写库覆盖原解析，比直接失败更糟。
     */
    val finishReason: String? = null
) {
    val truncated: Boolean get() = finishReason == "length"
}

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: ChatUsage? = null,
    val error: ApiError? = null
) {
    @Serializable
    data class Choice(
        val index: Int = 0,
        val message: ChatMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )

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
        // 非流式请求要等整段响应体到达才返回，readTimeout 就是「总等待上限」。
        // 深度解题走推理型模型 + 4096 输出预算，实际耗时经常超过 3 分钟；
        // 早先的 180s 会在长回答上稳定超时，表现为「有时能生成、有时不能」。
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // 不设 callTimeout：它会把重试等待也算进去，反而更容易误杀长请求
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun chat(
        provider: LlmProvider,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean = false,
        temperature: Double? = provider.temperature.toDouble(),
        maxTokens: Int? = null
    ): String = chatWithUsage(provider, systemPrompt, userPrompt, jsonMode, temperature, maxTokens).content

    suspend fun chatWithUsage(
        provider: LlmProvider,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean = false,
        temperature: Double? = provider.temperature.toDouble(),
        maxTokens: Int? = null
    ): ChatResult = chatWithMessages(
        provider = provider,
        messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userPrompt)
        ),
        jsonMode = jsonMode,
        temperature = temperature,
        maxTokens = maxTokens
    )

    /**
     * 多轮对话：把已经整理好的消息列表原样发送（system 必须在最前）。
     * 用于做题页的「追问 AI」——需要保留上下文才能连续追问。
     */
    suspend fun chatWithMessages(
        provider: LlmProvider,
        messages: List<ChatMessage>,
        jsonMode: Boolean = false,
        temperature: Double? = provider.temperature.toDouble(),
        maxTokens: Int? = null
    ): ChatResult = withContext(Dispatchers.IO) {
        runCatching { chatOnce(provider, messages, jsonMode, temperature, maxTokens) }
            .recoverCatching {
                // 部分兼容端点不支持 response_format / max_tokens，去掉后重试一次
                if (it is LlmException) chatOnce(provider, messages, false, temperature, null)
                else throw it
            }
            .getOrThrow()
    }

    private fun chatOnce(
        provider: LlmProvider,
        messages: List<ChatMessage>,
        jsonMode: Boolean,
        temperature: Double?,
        maxTokens: Int?
    ): ChatResult {
        val body = ChatCompletionRequest(
            model = provider.model,
            messages = messages,
            temperature = temperature,
            responseFormat = if (jsonMode) ChatCompletionRequest.ResponseFormat() else null,
            maxTokens = maxTokens
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
            val choice = parsed.choices.firstOrNull()
                ?: throw LlmException("模型未返回内容")
            val content = choice.message?.content.orEmpty()
            val reasoning = choice.message?.reasoningContent.orEmpty()
            if (content.isBlank()) {
                // 推理型模型偶发把整段预算花在思维链上 → content 为空但 reasoning 有内容。
                // 这时重试（换一次请求通常就能拿到正文）比让用户看到「未返回内容」更合理，
                // 抛 LlmException 也能被上层 isRetryable 捕捉到。
                throw LlmException(
                    if (reasoning.isNotBlank())
                        "模型只输出了思考过程未给出正文（可能被 max_tokens 截断），请重试"
                    else "模型未返回内容"
                )
            }
            ChatResult(content, parsed.usage ?: ChatUsage(), choice.finishReason)
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
