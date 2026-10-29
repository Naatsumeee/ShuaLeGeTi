package com.examhelper.shua.ui.generate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.examhelper.shua.ui.theme.CompactTopBar
import com.examhelper.shua.ui.theme.GlassSurface
import com.examhelper.shua.ui.theme.MonoText
import com.examhelper.shua.ui.theme.PaperCard
import com.examhelper.shua.ui.theme.SectionLabel
import com.examhelper.shua.ui.theme.SemanticChip
import com.examhelper.shua.ui.theme.ChipTone
import com.examhelper.shua.ui.theme.serifTitle
import com.examhelper.shua.ui.theme.safeBottomPadding
import com.examhelper.shua.ui.theme.safeTopPadding
import com.examhelper.shua.ui.theme.shuaColors

@Composable
fun GenerateScreen(
    bankId: Long,
    onBack: () -> Unit
) {
    val vm: GenerateViewModel = viewModel()
    val state by vm.state.collectAsState()
    val job = state.job
    val extra = shuaColors

    Column(Modifier.fillMaxSize().safeTopPadding().safeBottomPadding(minBottom = 6.dp)) {
        CompactTopBar(title = "AI 生成答案", onBack = onBack)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), elevation = 12.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatBlock("答案覆盖率", "${state.answerCoverage}%", "缺 ${state.noAnswer} 题", Modifier.weight(1f))
                            StatBlock("解析覆盖率", "${state.analysisCoverage}%", "缺 ${state.noAnalysis} 题", Modifier.weight(1f))
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(extra.creamLine))
                        Text(
                            if (state.sanSheng)
                                "三省六部 · 主部 ${state.primaryName} + ${state.providers - 1} 部交叉验证"
                            else
                                "单模型 · ${state.primaryName ?: "未配置"} · 每批 ${com.examhelper.shua.data.generate.AnswerGenerator.BATCH_SIZE} 题 × ${state.concurrency} 批并发",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.sanSheng) extra.terracotta else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 按章节限定重生成范围：题库很大时避免每次都全库扫描计费
            if (state.chapters.size > 1) {
                item {
                    SectionLabel(
                        "生成范围",
                        trailing = if (state.selectedChapters.isEmpty()) "全库"
                        else "${state.selectedChapters.size} 章 · ${state.scopedTotal} 题"
                    )
                }
                item {
                    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChipWrap {
                                SelectableMini(
                                    label = "全库",
                                    selected = state.selectedChapters.isEmpty()
                                ) { vm.clearChapters() }
                                state.chapters.forEach { c ->
                                    SelectableMini(
                                        label = c,
                                        selected = c in state.selectedChapters
                                    ) { vm.toggleChapter(c) }
                                }
                            }
                            Text(
                                "选中章节后，「生成缺失 / 全部重生成」只在所选章节内执行；不选则处理全库。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            if (job != null) {
                item { SectionLabel("生成进度", trailing = when (job.stage) {
                    "RUNNING" -> "后台运行中"
                    "DONE" -> "已完成"
                    "FAILED" -> "失败"
                    "CANCELED" -> "已取消"
                    else -> ""
                }) }
                item {
                    PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        "${job.done}",
                                        style = serifTitle(28.sp, FontWeight.Medium),
                                        color = extra.terracotta
                                    )
                                    Text(
                                        " / ${job.total}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 5.dp)
                                    )
                                }
                                if (job.stage == "RUNNING") {
                                    OutlinedButton(onClick = vm::cancel, shape = RoundedCornerShape(12.dp)) {
                                        Text("停止")
                                    }
                                }
                            }
                            if (job.total > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(job.done.toFloat() / job.total)
                                            .height(6.dp)
                                            .clip(CircleShape)
                                            .background(extra.terracotta)
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SemanticChip("成功 ${job.ok}", ChipTone.Brand)
                                SemanticChip("失败 ${job.fail}", ChipTone.Sage)
                            }
                            job.message?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (job.cacheHitTokens + job.cacheMissTokens > 0) {
                                val totalTok = job.cacheHitTokens + job.cacheMissTokens + job.outputTokens
                                val cost = com.examhelper.shua.data.generate.Cost.yuan(
                                    job.cacheHitTokens, job.cacheMissTokens, job.outputTokens
                                )
                                val rate = com.examhelper.shua.data.generate.Cost.hitRate(
                                    job.cacheHitTokens, job.cacheMissTokens
                                )
                                Text(
                                    "总 token ${com.examhelper.shua.data.generate.Cost.tokens(totalTok)}" +
                                        " / 缓存命中 $rate%" +
                                        " / 参考成本 ${com.examhelper.shua.data.generate.Cost.format(cost)} 元",
                                    style = monoStyleSafe(),
                                    color = extra.amber
                                )
                                Text(
                                    if (com.examhelper.shua.data.generate.Cost.isOffPeak())
                                        "按 DeepSeek 官方优惠时段（00:30–08:30）计价"
                                    else "按 DeepSeek 官方标准时段计价，00:30–08:30 为优惠时段",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            job.errorMessage?.let {
                                Text(it, color = extra.sage, style = MaterialTheme.typography.bodySmall)
                            }
                            // 卡死自愈：进程被杀/系统回收后数据库仍停在 RUNNING，给一键恢复入口
                            if (state.stalled) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(extra.creamLine))
                                Text(
                                    "进度已停在 ${job.done}/${job.total} 超过 3 分钟，后台任务可能已被系统回收。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = extra.amber
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            vm.healStalled()
                                            vm.start(false)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = extra.terracotta)
                                    ) { Text("标记中断并继续", style = MaterialTheme.typography.labelMedium) }
                                    OutlinedButton(
                                        onClick = vm::healStalled,
                                        shape = RoundedCornerShape(12.dp)
                                    ) { Text("仅清理状态", style = MaterialTheme.typography.labelMedium) }
                                }
                            }
                            if (state.log.isNotEmpty()) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(extra.creamLine))
                                state.log.takeLast(6).forEach { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.start(false) },
                        enabled = !state.running && state.scopedTodo > 0 && state.providers > 0,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("生成缺失 ${state.scopedIncomplete}", style = MaterialTheme.typography.labelLarge) }
                    OutlinedButton(
                        onClick = { vm.start(true) },
                        enabled = !state.running && state.providers > 0,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = extra.terracotta)
                    ) { Text("全部重生成", style = MaterialTheme.typography.labelLarge) }
                }
            }

            // 错误范围：AI 全部跑完后仍缺解析的题（有答案无解析），单独补漏
            item {
                PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "错误范围",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            SemanticChip("${state.scopedBroken} 题", ChipTone.Amber)
                        }
                        Text(
                            "已有答案但缺解析的题。用于批量补齐某一段解析，或修正早期版本遗留的覆盖空洞。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        OutlinedButton(
                            onClick = { vm.startBroken() },
                            enabled = !state.running && state.scopedBroken > 0 && state.providers > 0,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = extra.amber)
                        ) {
                            Text(
                                if (state.scopedBroken > 0) "补齐缺失解析 ${state.scopedBroken}"
                                else "解析已全覆盖",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            // 极简模式：解析压到 30 字，token 成本显著更低，适合只要答案的场景
            item {
                PaperCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(
                            "仅生成答案和极简解析",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "只处理缺答案或缺解析的题，解析限制 30 字以内，费用约为标准模式的一半。已生成过的题目自动跳过。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        OutlinedButton(
                            onClick = { vm.startMinimal() },
                            enabled = !state.running && state.scopedTodo > 0 && state.providers > 0,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = extra.terracotta)
                        ) {
                            Text("极简生成 ${state.scopedIncomplete}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            item {
                Text(
                    "已生成过的题目会自动跳过，中断后点「生成缺失」从断点继续，不重复计费。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, sub: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MonoText(value, size = 22.sp, color = MaterialTheme.colorScheme.primary)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

/** 弹性流式 chip 容器：窄屏自动折行，不产生横向滚动 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipWrap(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

@Composable
private fun SelectableMini(label: String, selected: Boolean, onClick: () -> Unit) {
    val extra = shuaColors
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) extra.terracottaSoft else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) extra.terracottaDeep else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun monoStyleSafe() = MaterialTheme.typography.labelSmall
