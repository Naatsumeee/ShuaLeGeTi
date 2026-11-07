package com.examhelper.shua.ui.sound

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 「按下去震一下 → 松开再震一下 → 才执行」的触感按钮。
 *
 * 用户要求的手感：
 * - **快速点击**（按下即松开）→ 清脆震一下就执行；
 * - **长按**（按住超过阈值）→ 按下瞬间震一下但**不执行**，松手再震一下才执行。
 *
 * 为什么不用 `combinedClickable` / `detectTapGestures`：这两个都只在
 * onLongPress 回调（约 500ms 后）给一次机会，**拿不到"按下"这一瞬间**，
 * 而用户要的正是按下就有一记触感。这里手动用 `awaitEachGesture` 走指针流，
 * 用 `withTimeoutOrNull` 判长按，两个阶段都能拿到。
 *
 * 阈值 340ms：比系统默认 500ms 短，手感更跟手，又不至于把普通快点的指纹
 * 误判成长按。
 */
fun Modifier.twoStageClick(
    enabled: Boolean = true,
    longPressThresholdMs: Long = 340L,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = composed {
    val clickState = rememberUpdatedState(onClick)
    val longState = rememberUpdatedState(onLongClick)
    val enabledState = rememberUpdatedState(enabled)
    this.pointerInput(longPressThresholdMs) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            // 阶段一：在阈值时间内等松开。正常返回 = 短按；超时 = 进入长按。
            val quickRelease = withTimeoutOrNull(longPressThresholdMs) {
                waitForRelease()
                true
            }
            if (quickRelease == true) {
                Haptics.tap()
                if (enabledState.value) clickState.value()
                return@awaitEachGesture
            }
            // 阶段二：按下这一记触感（不执行功能）
            Haptics.longPress()
            waitForRelease()
            // 松开这一记，然后才执行
            Haptics.longPress()
            if (!enabledState.value) return@awaitEachGesture
            longState.value?.invoke() ?: clickState.value()
        }
    }
}

/** 一直等到所有指针都抬起（或手势被取消） */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForRelease() {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) return
    }
}
