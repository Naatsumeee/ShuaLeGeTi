package com.examhelper.shua.ui.study

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.ui.stats.StatsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val durationWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 采集用户在做题页 / 测试页的前台停留时长并按天累加写入 `study_daily_durations`。
 * 每 15 秒心跳落盘一次，并在退后台或退出页面时补齐剩余时长。
 */
@Composable
fun TrackStudyDuration() {
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumedAt by remember { mutableLongStateOf(0L) }

    fun flush() {
        val start = resumedAt
        if (start <= 0L) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val delta = nowElapsed - start
        resumedAt = nowElapsed
        if (delta < 1000L) return
        val nowWall = System.currentTimeMillis()
        val dayKey = StatsViewModel.dayKey(nowWall)
        durationWriteScope.launch {
            runCatching {
                ShuaApp.instance.container.database.recordDao()
                    .addStudyDuration(dayKey, delta, nowWall)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && resumedAt == 0L) {
            resumedAt = SystemClock.elapsedRealtime()
        }
        while (true) {
            delay(15_000L)
            flush()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (resumedAt == 0L) resumedAt = SystemClock.elapsedRealtime()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    flush()
                    resumedAt = 0L
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            flush()
            resumedAt = 0L
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
