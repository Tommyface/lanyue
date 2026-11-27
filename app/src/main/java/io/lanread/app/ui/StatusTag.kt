package io.lanread.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 状态标签：kind 首段（$$ 前）为连载状态——
 * v0.4.23 配色：完结=澜光（secondary，水面归于平静）；
 * 连载中=赭签（tertiary，暖丝带——冷色水墨里唯一暖色强调）。
 */
@Composable
fun StatusTag(kind: String?) {
    val status = kind?.split("$$")?.firstOrNull()?.trim().orEmpty()
    if (status.isBlank()) return
    val fg = if (status.contains("完结")) MaterialTheme.colorScheme.secondary
    else MaterialTheme.colorScheme.tertiary
    Text(
        status,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(fg.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}
