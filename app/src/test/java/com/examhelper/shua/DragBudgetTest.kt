package com.examhelper.shua

import com.examhelper.shua.ui.sound.DragBudget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归护栏：首页「划动列表时误进入题库」。
 *
 * 根因是 `twoStageClick` 只判断手指抬没抬起、不看移动了多远，划动被判成短按直接
 * 执行了 onClick。这里守住判定的两条硬性质：**按累计值**、**超过阈值才算划动**。
 */
class DragBudgetTest {

    @Test
    fun `没动过不算划动`() {
        val b = DragBudget(slop = 24f)
        assertFalse(b.exceeded)
    }

    @Test
    fun `恰好等于阈值不算划动`() {
        // Compose 的 touchSlop 语义是「超过」，等于时还不算滚动
        val b = DragBudget(slop = 24f)
        b.add(24f)
        assertFalse(b.exceeded)
    }

    @Test
    fun `超过阈值就是划动`() {
        val b = DragBudget(slop = 24f)
        b.add(25f)
        assertTrue(b.exceeded)
    }

    @Test
    fun `多次小位移累加超过阈值也算划动`() {
        // 这是真实情况：一次划动被切成很多 delta，单个都远小于 slop。
        // 早期实现若只看「单次位移」会全部漏掉，所以必须累加。
        val b = DragBudget(slop = 24f)
        repeat(10) { b.add(3f) }   // 单次 3f < 24f，累计 30f > 24f
        assertTrue(b.exceeded)
    }

    @Test
    fun `来回滑动也累计`() {
        // 手指划出去再划回来，净位移可能接近 0，但它显然是一次拖动
        val b = DragBudget(slop = 24f)
        b.add(15f)
        b.add(15f)
        assertTrue(b.exceeded)
    }

    @Test
    fun `零和负数被忽略`() {
        val b = DragBudget(slop = 24f)
        b.add(0f)
        b.add(-100f)
        assertFalse(b.exceeded)
    }

    @Test
    fun `reset 后重新计数`() {
        val b = DragBudget(slop = 24f)
        b.add(30f)
        assertTrue(b.exceeded)
        b.reset()
        assertFalse(b.exceeded)
    }
}
