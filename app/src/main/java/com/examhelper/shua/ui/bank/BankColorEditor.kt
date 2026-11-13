package com.examhelper.shua.ui.bank

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.examhelper.shua.ui.theme.shuaColors
import java.util.Locale

/**
 * 题库组装饰条颜色编辑器。
 *
 * HSL 采用「饱和度 × 明度」二维色盘 + 独立色相条：
 * - 横轴 = 饱和度 0..100%
 * - 纵轴 = 明度 100%..0%
 * - 色相条用来选择整张色盘的基础色
 *
 * 点击或拖动色盘都会立即回写颜色；十六进制输入合法时也会立即应用。
 */
@Composable
internal fun BankColorEditor(
    initialColor: Color,
    onColorChange: (Color) -> Unit
) {
    val initialHsl = remember { BankColors.toHsl(initialColor) }
    var hue by remember { mutableFloatStateOf(initialHsl.hue) }
    var saturation by remember { mutableFloatStateOf(initialHsl.saturation) }
    var lightness by remember { mutableFloatStateOf(initialHsl.lightness) }
    var hex by remember { mutableStateOf(BankColors.toHex(initialColor)) }
    var hexError by remember { mutableStateOf<String?>(null) }
    val latestOnColorChange by rememberUpdatedState(onColorChange)

    fun syncColor(color: Color) {
        val hsl = BankColors.toHsl(color)
        hue = hsl.hue
        saturation = hsl.saturation
        lightness = hsl.lightness
        hex = BankColors.toHex(color)
        hexError = null
        latestOnColorChange(color)
    }

    fun updateHsl(newHue: Float = hue, newSaturation: Float = saturation, newLightness: Float = lightness) {
        hue = ((newHue % 360f) + 360f) % 360f
        saturation = newSaturation.coerceIn(0f, 1f)
        lightness = newLightness.coerceIn(0f, 1f)
        val color = BankColors.hsl(hue, saturation, lightness)
        hex = BankColors.toHex(color)
        hexError = null
        latestOnColorChange(color)
    }

    val selected = BankColors.hsl(hue, saturation, lightness)
    val extra = shuaColors

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("装饰条颜色", style = MaterialTheme.typography.labelMedium)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            BankColors.Presets.forEach { (label, color) ->
                val active = color.toArgb() == selected.toArgb()
                Box(
                    Modifier
                        .size(27.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            if (active) 2.dp else 1.dp,
                            if (active) MaterialTheme.colorScheme.onSurface else extra.creamLine,
                            CircleShape
                        )
                        .clickable { syncColor(color) },
                    contentAlignment = Alignment.Center
                ) {
                    if (active) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = label,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        Text(
            "HSL 色盘 · 点击或拖动选择",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, extra.creamLine, RoundedCornerShape(14.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun applyAt(position: Offset) {
                            updateHsl(
                                newHue = hue,
                                newSaturation = position.x / size.width,
                                newLightness = 1f - position.y / size.height
                            )
                        }
                        applyAt(down.position)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.pressed) applyAt(change.position)
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            // 以色块近似连续 HSL 平面。10px 粒度在手机上足够平滑，同时避免拖动时
            // 每帧绘制上万个小矩形造成掉帧。
            val step = 10f
            var y = 0f
            while (y < size.height) {
                val cellHeight = minOf(step, size.height - y)
                var x = 0f
                while (x < size.width) {
                    val cellWidth = minOf(step, size.width - x)
                    drawRect(
                        color = BankColors.hsl(
                            h = hue,
                            s = x / size.width,
                            l = 1f - y / size.height
                        ),
                        topLeft = Offset(x, y),
                        size = Size(cellWidth, cellHeight)
                    )
                    x += step
                }
                y += step
            }
            val marker = Offset(
                x = saturation * size.width,
                y = (1f - lightness) * size.height
            )
            drawCircle(
                color = Color.White,
                radius = 8.dp.toPx(),
                center = marker,
                style = Stroke(width = 3.dp.toPx())
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.65f),
                radius = 8.dp.toPx(),
                center = marker,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, extra.creamLine, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun applyAt(position: Offset) {
                            updateHsl(newHue = (position.x / size.width) * 360f)
                        }
                        applyAt(down.position)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.pressed) applyAt(change.position)
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(
                        Color.Red,
                        Color.Yellow,
                        Color.Green,
                        Color.Cyan,
                        Color.Blue,
                        Color.Magenta,
                        Color.Red
                    )
                )
            )
            val x = (hue / 360f) * size.width
            drawCircle(
                color = Color.White,
                radius = 7.dp.toPx(),
                center = Offset(x, size.height / 2f),
                style = Stroke(width = 3.dp.toPx())
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.65f),
                radius = 7.dp.toPx(),
                center = Offset(x, size.height / 2f),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(selected)
                    .border(1.dp, extra.creamLine, CircleShape)
            )
            Text(
                String.format(
                    Locale.US,
                    "H %03.0f° · S %02.0f%% · L %02.0f%%",
                    hue,
                    saturation * 100f,
                    lightness * 100f
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OutlinedTextField(
            value = hex,
            onValueChange = { raw ->
                hex = raw
                val parsed = BankColors.parseHex(raw)
                when {
                    raw.isBlank() -> hexError = null
                    parsed == null -> hexError = "颜色不合法，请输入 #RGB、#RRGGBB 或 #AARRGGBB（透明度需 ≥ 50%）"
                    else -> {
                        hexError = null
                        val hsl = BankColors.toHsl(parsed)
                        hue = hsl.hue
                        saturation = hsl.saturation
                        lightness = hsl.lightness
                        latestOnColorChange(parsed)
                    }
                }
            },
            label = { Text("自定义颜色，如 #D97757") },
            singleLine = true,
            isError = hexError != null,
            modifier = Modifier.fillMaxWidth()
        )
        hexError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
