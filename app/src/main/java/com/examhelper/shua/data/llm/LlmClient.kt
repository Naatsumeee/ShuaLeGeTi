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

/**
 * 思考强度（DeepSeek 官方口径，2026-11 文档核实）。
 *
 * `reasoning_effort` 只有 `none / low / high / max` 四个值：
 * - `none` 关闭思考模式；
 * - **`medium` 会被官方映射成 `high`**，所以这里不提供 medium 档 ——
 *   给用户一个「看着更低、实际一样贵」的选项比不给更糟；
 * - 官方默认是 `high`（不传 `reasoning_effort` 就默认开思考）。
 *
 * 这一条是实测出来的，不是猜的：同一段 5613 字的分块文本，
 * 不传参数（默认 high）时 8192 输出预算**被思考全额吃光**、`finish=length`、
 * 一道题都没解析出来；关掉思考后同样输入只需 6132 token 就 `finish=stop`、出 46 题。
 */
enum class ReasoningEffort(val apiValue: String, val label: String) {
    NONE("none", "Off"),
    LOW("low", "Low"),
    HIGH("high", "High"),
    MAX("max", "Max");

    companion object {
        fun fromName(raw: String?): ReasoningEffort =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: LOW
    }
}

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /**
     * 思考强度。`null` = 不传该字段（保持端点默认行为）。
     *
     * 必须能单独省略：不支持的端点（Ollama / 部分中转）收到未知字段有的会 400，
     * 所以调用方拿不到明确支持时就该传 null，让 [LlmClient] 的重试逻辑兜住。
     */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val stream: Boolean = false,
    /** 流式请求顺带要用量统计；不支持的端点会忽略它 */
    @SerialName("stream_options") val streamOptions: StreamOptions? = null
) {
    @Serializable
    data class ResponseFormat(val type: String = "json_object")
}

/** SSE 一帧解析出来的东西 */
internal data class SseFrame(
    val content: String? = null,
    val reasoning: String? = null,
    val finishReason: String? = null,
    val usage: ChatUsage? = null,
    val error: String? = null,
    val done: Boolean = false
)

/**
 * SSE 行解析（与网络分离，便于单测）。
 *
 * 各家实现的差异都在这里吸收：
 * - 心跳注释行 `: keep-alive`、空行 → 忽略；
 * - `data: [DONE]` → done；
 * - 部分中转把 `data:` 后面不加空格，或用 `event:` 行 → 都能过；
 * - usage 只在最后一帧出现（且 DSH 要求的 include_usage 不一定被支持）。
 */
internal object SseParser {
    fun parse(line: String, json: Json): SseFrame? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
        if (!trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return null
        if (payload == "[DONE]") return SseFrame(done = true)
        val chunk = runCatching { json.decodeFromString<StreamChunk>(payload) }.getOrNull()
            ?: return null
        chunk.error?.let { return SseFrame(error = it.message ?: "API 错误") }
        val choice = chunk.choices.firstOrNull()
        val delta = choice?.delta
        return SseFrame(
            content = delta?.content?.takeIf { it.isNotEmpty() },
            reasoning = delta?.reasoningContent?.takeIf { it.isNotEmpty() },
            finishReason = choice?.finishReason,
            usage = chunk.usage?.takeIf { it.promptTokens > 0 || it.completionTokens > 0 }
        )
    }
}

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = true)

/** SSE 增量帧里的 delta：正文与思维链可能分别下发 */
@Serializable
internal data class StreamDelta(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null
)

@Serializable
internal data class StreamChoice(
    val index: Int = 0,
    val delta: StreamDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
internal data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val usage: ChatUsage? = null,
    val error: ChatCompletionResponse.ApiError? = null
)

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
        maxTokens: Int? = null,
        reasoningEffort: ReasoningEffort? = null
    ): ChatResult = chatWithMessages(
        provider = provider,
        messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userPrompt)
        ),
        jsonMode = jsonMode,
        temperature = temperature,
        maxTokens = maxTokens,
        reasoningEffort = reasoningEffort
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
        maxTokens: Int? = null,
        reasoningEffort: ReasoningEffort? = null
    ): ChatResult = withContext(Dispatchers.IO) {
        runCatching { chatOnce(provider, messages, jsonMode, temperature, maxTokens, reasoningEffort) }
            .recoverCatching {
                // 两级降级，顺序不能反：
                // 先去掉 reasoning_effort —— 不认这个字段的端点（Ollama、部分中转）
                // 会直接 400。它是最"新"的参数，也最可能不被支持。
                if (it is LlmException) {
                    runCatching { chatOnce(provider, messages, jsonMode, temperature, maxTokens, null) }
                        // 再退一步：去掉 response_format / max_tokens（老端点只认最朴素的请求）
                        .recoverCatching {
                            if (it is LlmException) chatOnce(provider, messages, false, temperature, null, null)
                            else throw it
                        }.getOrThrow()
                } else throw it
            }
            .getOrThrow()
    }

    /**
     * 流式对话（SSE）：每收到一小段正文就通过 [onDelta] 抛给调用方，界面因此能逐字显示。
     *
     * 兼容性优先：
     * - 端点支持 `stream: true` → 按 `data:` 行逐帧解析；
     * - 端点忽略 stream、直接回整包 JSON（不少中转如此）→ 自动退化为一次性回调，结果一致；
     * - 思维链（`reasoning_content`）单独走 [onReasoning]，与正文分开渲染。
     */
    suspend fun chatStream(
        provider: LlmProvider,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean = false,
        temperature: Double? = provider.temperature.toDouble(),
        maxTokens: Int? = null,
        reasoningEffort: ReasoningEffort? = null,
        onReasoning: (String) -> Unit = {},
        onDelta: (String) -> Unit = {}
    ): ChatResult = chatStreamMessages(
        provider = provider,
        messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userPrompt)
        ),
        jsonMode = jsonMode,
        temperature = temperature,
        maxTokens = maxTokens,
        reasoningEffort = reasoningEffort,
        onReasoning = onReasoning,
        onDelta = onDelta
    )

    suspend fun chatStreamMessages(
        provider: LlmProvider,
        messages: List<ChatMessage>,
        jsonMode: Boolean = false,
        temperature: Double? = provider.temperature.toDouble(),
        maxTokens: Int? = null,
        reasoningEffort: ReasoningEffort? = null,
        onReasoning: (String) -> Unit = {},
        onDelta: (String) -> Unit = {}
    ): ChatResult = withContext(Dispatchers.IO) {
        // 已经吐出过正文就不要重试了：重试会把同一段话再说一遍，
        // 界面上表现为「回答重复了两遍」，比直接报错更让人困惑。
        var emitted = false
        val trackDelta: (String) -> Unit = { d -> emitted = true; onDelta(d) }
        runCatching {
            streamOnce(provider, messages, jsonMode, temperature, maxTokens, reasoningEffort, onReasoning, trackDelta)
        }.recoverCatching { first ->
            // 部分兼容端点不认 response_format / max_tokens / stream_options，
            // 去掉这些可选字段再试一次（仍保留 stream，流式是本次的核心诉求）。
            if (first !is LlmException || emitted) throw first
            // 第一级：只去掉 reasoning_effort（新参数，最可能不被支持）
            val second = runCatching {
                streamOnce(provider, messages, jsonMode, temperature, maxTokens, null, onReasoning, trackDelta)
            }
            if (second.isSuccess) return@recoverCatching second.getOrThrow()
            // 第二级：连 response_format / max_tokens 一起去掉
            if (emitted) throw first
            streamOnce(provider, messages, false, temperature, null, null, onReasoning, trackDelta)
        }.getOrThrow()
    }

    private fun streamOnce(
        provider: LlmProvider,
        messages: List<ChatMessage>,
        jsonMode: Boolean,
        temperature: Double?,
        maxTokens: Int?,
        reasoningEffort: ReasoningEffort?,
        onReasoning: (String) -> Unit,
        onDelta: (String) -> Unit
    ): ChatResult {
        val body = ChatCompletionRequest(
            model = provider.model,
            messages = messages,
            temperature = temperature,
            responseFormat = if (jsonMode) ChatCompletionRequest.ResponseFormat() else null,
            maxTokens = maxTokens,
            reasoningEffort = reasoningEffort?.apiValue,
            stream = true,
            streamOptions = StreamOptions()
        )
        val url = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(json.encodeToString(body).toRequestBody(jsonMediaType))
            .build()

        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw LlmException("HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(300)}")
            }
            val source = resp.body?.source() ?: throw LlmException("响应为空")
            val first = source.readUtf8Line()
                ?: throw LlmException("模型未返回内容")

            // 非 SSE：端点不支持流式，整包 JSON 一次到达 → 一次性回调，语义与流式一致
            if (!first.trimStart().startsWith("data:")) {
                val whole = first + "\n" + source.readString(Charsets.UTF_8)
                val parsed = runCatching { json.decodeFromString<ChatCompletionResponse>(whole) }
                    .getOrElse { throw LlmException("响应解析失败: ${whole.take(200)}", it) }
                parsed.error?.let { throw LlmException(it.message ?: "API 错误") }
                val choice = parsed.choices.firstOrNull() ?: throw LlmException("模型未返回内容")
                val content = choice.message?.content.orEmpty()
                choice.message?.reasoningContent?.takeIf { it.isNotBlank() }?.let(onReasoning)
                if (content.isBlank()) {
                    throw LlmException("模型未返回内容")
                }
                onDelta(content)
                return@use ChatResult(content, parsed.usage ?: ChatUsage(), choice.finishReason)
            }

            val content = StringBuilder()
            val reasoning = StringBuilder()
            var finishReason: String? = null
            var usage = ChatUsage()
            var done = false

            fun handleLine(line: String) {
                val frame = SseParser.parse(line, json) ?: return
                frame.error?.let { throw LlmException(it) }
                frame.usage?.let { usage = it }
                frame.finishReason?.let { finishReason = it }
                frame.reasoning?.let {
                    reasoning.append(it)
                    onReasoning(it)
                }
                frame.content?.let {
                    content.append(it)
                    onDelta(it)
                }
                if (frame.done) done = true
            }

            handleLine(first)
            while (!done) {
                val line = source.readUtf8Line() ?: break
                handleLine(line)
            }

            if (content.isBlank()) {
                throw LlmException(
                    if (reasoning.isNotBlank())
                        "模型只输出了思考过程未给出正文（可能被 max_tokens 截断），请重试"
                    else "模型未返回内容"
                )
            }
            ChatResult(content.toString(), usage, finishReason)
        }
    }

    private fun chatOnce(
        provider: LlmProvider,
        messages: List<ChatMessage>,
        jsonMode: Boolean,
        temperature: Double?,
        maxTokens: Int?,
        reasoningEffort: ReasoningEffort? = null
    ): ChatResult {
        val body = ChatCompletionRequest(
            model = provider.model,
            messages = messages,
            temperature = temperature,
            responseFormat = if (jsonMode) ChatCompletionRequest.ResponseFormat() else null,
            maxTokens = maxTokens,
            reasoningEffort = reasoningEffort?.apiValue
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
