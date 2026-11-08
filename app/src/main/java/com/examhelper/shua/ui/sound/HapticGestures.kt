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
 *
 * **必须让位于子级可点击组件**：卡片右上角的「更多」按钮是卡片里的子级
 * `clickable`，Compose 的指针事件在 Main pass 里是从子级往父级冒泡的，
 * 子级点击会 consume 掉事件。早期版本不看 consume 状态，于是点一次菜单按钮
 * 会同时触发「父级短按 = 进入题库」+「子级 = 展开菜单」——菜单弹出来的同时
 * 页面已经跳走，看起来就像「菜单显示了但点了没反应」。这里在按下与等待松开
 * 两个环节都检查 `isConsumed`，一旦被子级接管就整段放弃，连触感也不给。
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
            val down = awaitFirstDown(requireUnconsumed = false)
            // 起手就被子级按钮接管（右上角更多、内部 IconButton 等）→ 整段让出
            if (down.isConsumed) return@awaitEachGesture
            // 阶段一：在阈值时间内等松开。正常返回 = 短按；超时 = 进入长按。
            var consumedByChild = false
            val quickRelease = withTimeoutOrNull(longPressThresholdMs) {
                consumedByChild = waitForRelease()
                true
            }
            if (consumedByChild) return@awaitEachGesture
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

/**
 * 一直等到所有指针都抬起（或手势被取消）。
 *
 * 返回 true 表示有指针的 up 事件已经被子级 consume（子级可点击组件吃掉了），
 * 调用方应当放弃本次手势、不要再去执行自己的 onClick。
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForRelease(): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.any { it.isConsumed && !it.pressed }) return true
        if (event.changes.none { it.pressed }) return false
    }
}
