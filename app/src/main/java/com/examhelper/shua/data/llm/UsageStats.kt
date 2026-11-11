package com.examhelper.shua.data.llm

import com.examhelper.shua.data.db.BalanceSnapshot
import com.examhelper.shua.data.db.BalanceSnapshotDao
import com.examhelper.shua.data.db.GenerateJobDao
import com.examhelper.shua.data.db.ImportJobDao
import com.examhelper.shua.data.db.LlmProvider
import com.examhelper.shua.data.generate.Cost

/**
 * 「用量情况」统计：近 N 天的消费。
 *
 * ## 为什么不能直接查 API
 *
 * DeepSeek 只有 `GET /user/balance`（当前余额），**没有**用量/账单接口 ——
 * `/user/usage`、`/usage`、`/user/bills`、`/billing`、`/user/consumption`、
 * `/dashboard/billing/usage` 全部 404（实测）。所以消费额只能自己算。
 *
 * ## 两条互相独立的算法
 *
 * 1. **余额下降累计（实际）**：把窗口内按时间排序的快照，相邻两次的**下降量**累加；
 *    余额上升视为充值，整段跳过。结算延迟只有几分钟，7/30 天窗口足够把它稀释掉，
 *    所以这个数字能和官方账单对上。
 *
 * 2. **本地 token 估算（参考）**：用 `import_jobs` / `generate_jobs` 里记录的
 *    hit/miss/output token，按每条记录自己的时间戳判峰谷价再累加。
 *    它的强项是立刻给出结果、且能拆出 token 构成；弱项是依赖端点透传缓存字段。
 *
 * 两者并列展示，差值本身也是信息：差得远说明端点没透传缓存字段，或价目表变了。
 */
object UsageStats {

    /** 采样节流：任务可能在几分钟内连发，不必每次都打官方接口 */
    const val SAMPLE_MIN_INTERVAL_MS = 30 * 60 * 1000L

    /** 应用打开时的采样节流：每天最多一次 */
    const val DAILY_SAMPLE_INTERVAL_MS = 20 * 60 * 60 * 1000L

    /** 可选窗口 */
    val WINDOWS = listOf(7, 30)

    data class Window(
        val days: Int,
        /** 余额下降累计（实际消费）；快照不足时为 null */
        val actualSpent: Double?,
        /** 本地 token 估算；没有任务记录时为 null */
        val estimateSpent: Double?,
        val hitTokens: Long = 0,
        val missTokens: Long = 0,
        val outTokens: Long = 0,
        val taskCount: Int = 0,
        val currency: String = "CNY",
        /** 参与了差额计算的快照条数（<2 时算不出实际消费） */
        val snapshotCount: Int = 0,
        /** 窗口内最早一条快照的时间；UI 用它说明「自 X 起」 */
        val coveredSince: Long? = null
    ) {
        val totalTokens: Long get() = hitTokens + missTokens + outTokens
        val hasActual: Boolean get() = actualSpent != null
        val hasEstimate: Boolean get() = estimateSpent != null
        val isEmpty: Boolean get() = !hasActual && !hasEstimate
    }

    /**
     * 采一次余额快照。端点不支持查余额（中转站）、key 无效、网络失败都静默跳过 ——
     * 用量统计是附加信息，绝不能因为它失败而影响任务本身。
     *
     * @param minIntervalMs 距上次快照不足这个间隔就跳过；传 0 表示强制采
     */
    suspend fun sample(
        client: BalanceClient,
        dao: BalanceSnapshotDao,
        provider: LlmProvider?,
        minIntervalMs: Long = SAMPLE_MIN_INTERVAL_MS
    ): BalanceSnapshot? {
        if (provider == null || !client.supports(provider)) return null
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0) {
            val last = runCatching { dao.latest(provider.id) }.getOrNull()
            if (last != null && now - last.sampledAt < minIntervalMs) return last
        }
        val snap = BalanceLedger.read(client, provider) ?: return null
        val row = BalanceSnapshot(
            providerId = provider.id,
            amount = snap.amount,
            currency = snap.currency,
            sampledAt = now
        )
        runCatching { dao.insert(row) }
        return row
    }

    /**
     * 算窗口用量。[model] 用来挑价目（认不出就只出 token 数、不出金额）。
     */
    suspend fun compute(
        snapshotDao: BalanceSnapshotDao,
        importDao: ImportJobDao,
        generateDao: GenerateJobDao,
        providerId: Long,
        days: Int,
        model: String?
    ): Window {
        val now = System.currentTimeMillis()
        val since = now - days * 24L * 60 * 60 * 1000

        // ---- ① 余额下降累计 ----
        val inWindow = runCatching { snapshotDao.since(providerId, since) }.getOrDefault(emptyList())
        val beforeWindow = runCatching { snapshotDao.lastBefore(providerId, since) }.getOrNull()
        // 窗口起点之前那条必须带进来：窗口开头那段的消费就体现在它和首条窗口内快照的差里
        val series = (listOfNotNull(beforeWindow) + inWindow).sortedBy { it.sampledAt }
        // **至少两条快照才算得出来**：只有一条时没有任何「变化」可算，
        // 返回 0.0 会和「确实没消费」混为一谈（这正是「缓存命中 0%」那类误导的同型错误）。
        val actual: Double? = if (series.size < 2) null else sumDecreases(series.map { it.amount })
        val currency = series.lastOrNull()?.currency ?: "CNY"
        // ---- ② 本地 token 估算 ----
        val imports = runCatching { importDao.since(since) }.getOrDefault(emptyList())
            .filter { it.cacheHitTokens + it.cacheMissTokens + it.outputTokens > 0 }
        val generates = runCatching { generateDao.since(since) }.getOrDefault(emptyList())
            .filter { it.cacheHitTokens + it.cacheMissTokens + it.outputTokens > 0 }

        var hit = 0L
        var miss = 0L
        var out = 0L
        var estimate = 0.0
        var priced = false
        imports.forEach { j ->
            hit += j.cacheHitTokens; miss += j.cacheMissTokens; out += j.outputTokens
            // 每条按自己的时间判峰谷：一批生成跑了半小时、跨了 18:00，按结束时刻统一
            // 算成高峰会把空闲时段的活多算一倍
            Cost.yuan(j.cacheHitTokens, j.cacheMissTokens, j.outputTokens, model, j.createdAt)
                ?.let { estimate += it; priced = true }
        }
        generates.forEach { j ->
            hit += j.cacheHitTokens; miss += j.cacheMissTokens; out += j.outputTokens
            Cost.yuan(j.cacheHitTokens, j.cacheMissTokens, j.outputTokens, model, j.updatedAt)
                ?.let { estimate += it; priced = true }
        }

        return Window(
            days = days,
            actualSpent = actual,
            estimateSpent = if (priced) estimate else null,
            hitTokens = hit,
            missTokens = miss,
            outTokens = out,
            taskCount = imports.size + generates.size,
            currency = currency,
            snapshotCount = series.size,
            coveredSince = series.firstOrNull()?.sampledAt
        )
    }

    /**
     * 把一串按时间升序的余额，累加成「消费总额」。
     *
     * 规则只有一条：**只累加下降量**。
     * - 下降 = 消费；
     * - 上升 = 充值（或赠送到账），整段跳过，绝不能当成负消费抵掉真实支出；
     * - 持平 = 还没结算，记 0。
     *
     * 已知局限：如果「充值」和「消费」落在同一次采样区间里，两者会互相抵消，
     * 那段消费就丢了。采样越密丢得越少 —— 应用打开（每天一次）与任务前后都采，
     * 就是为了把这种区间压到最短。
     */
    fun sumDecreases(amounts: List<Double>): Double {
        if (amounts.size < 2) return 0.0
        var sum = 0.0
        for (i in 1 until amounts.size) {
            val drop = amounts[i - 1] - amounts[i]
            if (drop > 0) sum += drop
        }
        return sum
    }

    /** 清理过期快照：只留 [days] 天（默认比最大窗口多留几天余量） */
    suspend fun purge(dao: BalanceSnapshotDao, days: Int = 40) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        runCatching { dao.purgeBefore(cutoff) }
    }
}
