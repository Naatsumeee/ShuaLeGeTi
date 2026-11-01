package com.examhelper.shua.data.llm

import com.examhelper.shua.data.db.LlmProvider
import kotlinx.coroutines.delay

/**
 * 任务前后各查一次官方余额，差额就是本轮真实花费。
 *
 * 为什么要走「余额差」这条线：本地按 token 单价算的成本依赖三个上游事实 ——
 * 中转端点透传 `prompt_cache_hit_tokens`、并发分批的用量被完整归集、价目表没变。
 * 任何一个不成立，用户看到的数字就和 DeepSeek 账单对不上（用户反馈的「显示缓存命中 0%
 * 且和实际差别较大」正是第一个原因）。余额是官方账本，差值不需要相信任何中间环节。
 *
 * 结算有延迟：接口返回的是当前已结算余额，任务刚结束时最后几批可能还在路上，
 * 所以 [after] 带重试，直到两次读数稳定或次数用尽。
 */
object BalanceLedger {

    /** 一次余额快照；[currency] 用于 UI 标注（接口默认 CNY） */
    data class Snapshot(val amount: Double, val currency: String)

    /**
     * 读一次余额。端点不支持（中转站没有 /user/balance）、key 无效、网络失败
     * 一律返回 null —— 余额核算只是附加信息，绝不能因为它失败而中断导入/生成。
     */
    suspend fun read(client: BalanceClient, provider: LlmProvider?): Snapshot? {
        if (provider == null || !client.supports(provider)) return null
        val result = client.fetch(provider).getOrNull() ?: return null
        val info = result.primary ?: return null
        return Snapshot(info.total, info.currency.ifBlank { "CNY" })
    }

    /**
     * 任务结束后的读数：余额结算有延迟，隔 [intervalMs] 重读若干次，
     * 一旦连续两次读数相同就认为已结算，返回最后一次。
     * 一次都读不到就返回 null（调用方据此只显示参考成本）。
     */
    suspend fun readSettled(
        client: BalanceClient,
        provider: LlmProvider?,
        attempts: Int = 4,
        intervalMs: Long = 1_200
    ): Snapshot? {
        var last: Snapshot? = null
        var stable = 0
        repeat(attempts.coerceAtLeast(1)) { i ->
            val cur = read(client, provider)
            if (cur != null) {
                if (last != null && last!!.amount == cur.amount) {
                    stable++
                    if (stable >= 1) return cur
                }
                last = cur
            }
            if (i < attempts - 1) delay(intervalMs)
        }
        return last
    }
}
