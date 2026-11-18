package com.examhelper.shua.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun QuestionNumberBadge(
    number: Int,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val text = number.toString()
    val minSize = if (compact) 21.dp else 26.dp
    val fontSize = when {
        text.length >= 4 -> 9.sp
        text.length == 3 -> 10.sp
        else -> 11.sp
    }
    Box(
        modifier
            .heightIn(min = minSize)
            .widthIn(min = minSize)
            .clip(CircleShape)
            .background(shuaColors.terracottaSoft)
            .padding(horizontal = if (text.length > 2) 5.dp else 0.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = monoStyle(fontSize, FontWeight.Medium),
            color = shuaColors.terracotta,
            maxLines = 1,
            softWrap = false
        )
    }
}
