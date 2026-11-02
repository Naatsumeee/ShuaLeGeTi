package com.examhelper.shua.data.ocr

/**
 * 纯 JVM 的矩形（不依赖 android.graphics.Rect）。
 *
 * 为什么要自己写一个：OCR 的分栏/排序逻辑是纯几何计算，应该能被普通单元测试覆盖。
 * 用 android.graphics.Rect 的话，未开启 returnDefaultValues 的单测里构造直接抛
 * 「not mocked」，这些最关键的重排逻辑就测不了。
 */
data class Box(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun contains(x: Int, y: Int): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    companion object {
        /** 从四点坐标构造，自动纠正颠倒的边界（旋转框投影后偶尔会左>右） */
        fun of(x1: Int, y1: Int, x2: Int, y2: Int) = Box(
            minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2)
        )
    }
}
