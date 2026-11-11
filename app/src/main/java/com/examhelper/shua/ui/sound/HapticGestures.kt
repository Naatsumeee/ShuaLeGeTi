package com.examhelper.shua.ui.sound

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

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
 *
 * **必须让位于父级滚动（v1.1.1 修复）**：早期实现只判断「手指抬起来了没有」，
 * 完全不管这期间手指移动了多远。于是**在首页上下划动列表时会误进入题库**——
 * 划动通常在 340ms 内完成，被判成「短按」直接执行了 onClick，而列表同时也在滚。
 * 现在用 [DragBudget] 累计位移，一旦超过 `touchSlop` 就整段放弃（不震、不点、不消费事件），
 * 滚动照常交给父级的 LazyColumn。
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
            val budget = DragBudget(viewConfiguration.touchSlop)

            // 阶段一：在阈值时间内等松开。超时 = 进入长按。
            val quick = withTimeoutOrNull(longPressThresholdMs) {
                waitForReleaseOrDrag(down.id, budget)
            }
            // 这段时间里手指已经划出去了 → 是滚动手势，什么都不做
            if (quick == ReleaseResult.DRAG || budget.exceeded) return@awaitEachGesture
            if (quick == ReleaseResult.CONSUMED_BY_CHILD) return@awaitEachGesture
            if (quick == ReleaseResult.RELEASED) {
                Haptics.tap()
                if (enabledState.value) clickState.value()
                return@awaitEachGesture
            }

            // 阶段二：按下这一记触感（不执行功能）
            Haptics.longPress()
            when (waitForReleaseOrDrag(down.id, budget)) {
                // 按住之后又划走了（长按拖动）→ 同样只当滚动，不执行任何功能
                ReleaseResult.DRAG -> return@awaitEachGesture
                ReleaseResult.CONSUMED_BY_CHILD -> return@awaitEachGesture
                ReleaseResult.RELEASED -> Unit
            }
            // 松开这一记，然后才执行
            Haptics.longPress()
            if (!enabledState.value) return@awaitEachGesture
            longState.value?.invoke() ?: clickState.value()
        }
    }
}

/** 一次手势「等了半天」之后到底算什么。 */
private enum class ReleaseResult {
    /** 手指抬起，且中途没有划动 → 这是一次点按 */
    RELEASED,

    /** 位移超过 touchSlop → 这是滚动/拖动，不是点按 */
    DRAG,

    /** 抬起事件被子级可点击组件吃掉了 */
    CONSUMED_BY_CHILD
}

/**
 * 累计手指位移，判断这次手势是「点按」还是「划动」。
 *
 * 单独抽成一个类而不是内联变量，是为了能在纯 JVM 单测里锁住阈值行为
 * （Compose 的指针流在无 Robolectric 的单测里没法跑）。两个必须守住的性质：
 * - **按累计值判断**，不是单次位移：一次划动会被切成很多个小 delta，每个都可能远小于
 *   touchSlop，只有累加才能识别出这是一次划动。
 * - 阈值用 `>` 而不是 `>=`，与 Compose 的 `touchSlop` 语义一致（超过才算滚动）。
 */
internal class DragBudget(private val slop: Float) {
    private var moved = 0f

    val exceeded: Boolean get() = moved > slop

    /** 累加一次位移量；负数/零（脏数据）直接忽略。 */
    fun add(distance: Float) {
        if (distance > 0f) moved += distance
    }

    fun reset() {
        moved = 0f
    }
}

/**
 * 一直等到所有指针都抬起（或手势被取消），期间持续累计位移。
 *
 * - 位移超过 touchSlop → 立刻返回 [ReleaseResult.DRAG]，调用方必须放弃本次手势；
 * - 有指针的 up 事件已经被子级 consume → 返回 [ReleaseResult.CONSUMED_BY_CHILD]；
 * - 正常抬起 → 返回 [ReleaseResult.RELEASED]。
 */
private suspend fun AwaitPointerEventScope.waitForReleaseOrDrag(
    pointerId: PointerId,
    budget: DragBudget
): ReleaseResult {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.any { it.isConsumed && !it.pressed }) return ReleaseResult.CONSUMED_BY_CHILD
        val main = event.changes.firstOrNull { it.id == pointerId }
            ?: return ReleaseResult.RELEASED
        if (!main.pressed) return ReleaseResult.RELEASED
        budget.add((main.position - main.previousPosition).getDistance())
        if (budget.exceeded) return ReleaseResult.DRAG
    }
}
