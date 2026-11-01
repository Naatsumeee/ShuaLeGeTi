package com.examhelper.shua.data.llm

import com.examhelper.shua.data.db.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 单个币种的余额 */
@Serializable
data class BalanceInfo(
    val currency: String = "CNY",
    /** 总可用余额 = 赠送 + 充值 */
    @SerialName("total_balance") val totalBalance: String = "0",
    /** 未过期的赠送余额 */
    @SerialName("granted_balance") val grantedBalance: String = "0",
    /** 充值余额 */
    @SerialName("topped_up_balance") val toppedUpBalance: String = "0"
) {
    val total: Double get() = totalBalance.toDoubleOrNull() ?: 0.0
}

@Serializable
data class AccountBalance(
    @SerialName("is_available") val isAvailable: Boolean = false,
    @SerialName("balance_infos") val balanceInfos: List<BalanceInfo> = emptyList()
) {
    /** 优先取人民币，没有就取第一条 */
    val primary: BalanceInfo?
        get() = balanceInfos.firstOrNull { it.currency.equals("CNY", true) }
            ?: balanceInfos.firstOrNull()
}

/**
 * DeepSeek 官方「查询余额」接口（GET /user/balance）。
 *
 * 只对**官方端点**有意义：中转站/兼容端点没有这个路由，直接请求只会拿到 404 或
 * 一段 HTML。所以先用 [supports] 判断，判断不过就不要发请求。
 *
 * 用途不只是「看一眼还剩多少钱」——它还是校验本地成本估算的唯一权威来源：
 * 一次生成任务前后各查一次，余额差就是真实扣费，可与 app 自己算的「参考成本」对照。
 * 文档：https://api-docs.deepseek.com/zh-cn/api/get-user-balance/
 */
class BalanceClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 该模型配置是否能查余额（只有 DeepSeek 官方域名有 /user/balance） */
    fun supports(provider: LlmProvider?): Boolean {
        val base = provider?.baseUrl?.lowercase() ?: return false
        return base.contains("deepseek.com") || base.contains("api.deepseek")
    }

    suspend fun fetch(provider: LlmProvider): Result<AccountBalance> = withContext(Dispatchers.IO) {
        runCatching {
            if (!supports(provider)) {
                throw LlmException("该端点不支持余额查询（仅 DeepSeek 官方 api.deepseek.com 提供）")
            }
            val url = provider.baseUrl.trimEnd('/') + "/user/balance"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${provider.apiKey}")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw LlmException("HTTP ${resp.code}: ${text.take(200)}")
                }
                runCatching { json.decodeFromString<AccountBalance>(text) }
                    .getOrElse { throw LlmException("余额响应解析失败: ${text.take(160)}", it) }
            }
        }
    }
}
