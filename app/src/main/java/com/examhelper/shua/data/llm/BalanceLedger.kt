package com.examhelper.shua.data.llm

import com.examhelper.shua.data.db.LlmProvider
import kotlinx.coroutines.delay

/**
 * 读官方余额。
 *
 * ## ⚠️ 不要再拿「任务前后差值」当实扣
 *
 * DeepSeek 的计费**不是实时同步**的，通常有几分钟延迟。任务刚结束时前后两次读数
 * 几乎总是相等 —— 实测某任务前后都是 61.29，而它实际消耗了 7431 输入 + 8997 输出 token。
 * 据此算出来的「实扣」永远是 0，比不显示更误导人，所以这个功能已经整个删掉了。
 *
 * 想要「实际消费」，正确做法是**多天累计**：把余额读成快照存下来，
 * 在窗口内累加所有下降量（充值回升跳过）。几分钟的延迟在 7/30 天窗口里可以忽略 ——
 * 见 [UsageStats]。
 *
 * 下面这段是当初为什么以为余额差可靠的推理，保留作记录：本地按 token 单价算的成本
 * 依赖三个上游事实（端点透传 `prompt_cache_hit_tokens`、并发用量被完整归集、价目表没变），
 * 任何一个不成立都会和账单对不上。结论没错 —— 错在以为任务级的差值能承担这件事。
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
     *
     * **已无调用方**：它就是「实扣」的实现，而实扣因计费延迟恒为 0，已删除。
     * 保留仅因它可能被未来某个「等结算稳定」的场景复用；别再用来算单任务实扣。
     */
    @Deprecated("单任务实扣因计费延迟恒为 0，已改用 UsageStats 的多天累计")
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
