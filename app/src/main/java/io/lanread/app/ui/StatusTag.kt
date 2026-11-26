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
 * 含“完结”用主色系，其余（连载中等）用红系（浅底深字，图 1 样式）。
 */
@Composable
fun StatusTag(kind: String?) {
    val status = kind?.split("$$")?.firstOrNull()?.trim().orEmpty()
    if (status.isBlank()) return
    val fg = if (status.contains("完结")) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.error
    Text(
        status,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(fg.copy(alpha = 0.10f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}
