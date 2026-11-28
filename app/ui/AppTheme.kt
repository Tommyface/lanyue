package io.lanread.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * 澜阅视觉识别（v0.4.23 重绘）——「澜」的水与「阅」的书卷：
 *  澜靛 #24586F（主色，砚池深水上的靛青）
 *  澜光 #6FA8BC（辅色，水面反光）
 *  宣纸 #F5F1E5（页面底色，暖白纸）
 *  赭签 #B97F4E（强调色，书签丝带——冷色水墨里唯一的暖，只给关键动作）
 *  夜砚 #0E161B（暗色底）
 * 字体角色：标题=平台衬线（宋体族，克制使用）；正文=系统无衬线；页码等数字=等宽字。
 * 签名元素：书架条目的书脊色带（BookSpine）+ 空态水纹插画。
 */
object LanType {
    val Display = FontFamily.Serif      // 标题：书卷衬线
    val Folio = FontFamily.Monospace    // 页码/计数：folio 数字
}

/** 书脊色带四色（书架条目左侧竖带，按书 id 哈希分配——每本书有自己的书脊） */
object SpinePalette {
    private val colors = listOf(
        Color(0xFF24586F),   // 澜靛
        Color(0xFF4C7A5A),   // 竹青
        Color(0xFF8C5A38),   // 赭木
        Color(0xFF5B4A6E)    // 紫檀
    )
    fun of(bookId: Long): Color = colors[(bookId % colors.size + colors.size).toInt() % colors.size]
}

private val InkLight = lightColorScheme(
    primary = Color(0xFF24586F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCADFE6),
    onPrimaryContainer = Color(0xFF0D2A36),
    secondary = Color(0xFF4A8599),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E6EC),
    onSecondaryContainer = Color(0xFF0E3542),
    tertiary = Color(0xFFB97F4E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6E4D2),
    onTertiaryContainer = Color(0xFF4A2F17),
    background = Color(0xFFF5F1E5),
    onBackground = Color(0xFF1F292E),
    surface = Color(0xFFFDFAF2),
    onSurface = Color(0xFF1F292E),
    surfaceVariant = Color(0xFFE8E2D2),
    onSurfaceVariant = Color(0xFF575D62),
    outline = Color(0xFFD5CEBB),
    outlineVariant = Color(0xFFE2DCC9),
    error = Color(0xFFA63A2E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6DCD6),
    onErrorContainer = Color(0xFF411109)
)

private val InkNight = darkColorScheme(
    primary = Color(0xFFA3CCDA),
    onPrimary = Color(0xFF0B2E3A),
    primaryContainer = Color(0xFF264957),
    onPrimaryContainer = Color(0xFFC9E6EE),
    secondary = Color(0xFF93BFCB),
    onSecondary = Color(0xFF0F3540),
    secondaryContainer = Color(0xFF2C4E59),
    onSecondaryContainer = Color(0xFFBEDAE2),
    tertiary = Color(0xFFD3A377),
    onTertiary = Color(0xFF3A2410),
    tertiaryContainer = Color(0xFF55391F),
    onTertiaryContainer = Color(0xFFF2D9BC),
    background = Color(0xFF0E161B),
    onBackground = Color(0xFFDEE3E0),
    surface = Color(0xFF121B21),
    onSurface = Color(0xFFDEE3E0),
    surfaceVariant = Color(0xFF384046),
    onSurfaceVariant = Color(0xFFBCC4C8),
    outline = Color(0xFF424A50),
    outlineVariant = Color(0xFF384046),
    error = Color(0xFFDDA9A0),
    onError = Color(0xFF3C0A04),
    errorContainer = Color(0xFF5E130B),
    onErrorContainer = Color(0xFFF6DCD6)
)

@Composable
fun LanTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) InkNight else InkLight,
        content = content
    )
}

/** 阅读页主题（背景 / 正文 / 次要文字）：纸、宣、竹、夜 */
data class ReaderTheme(val name: String, val bg: Color, val text: Color, val dim: Color)

val ReaderThemes = listOf(
    ReaderTheme("纸", Color(0xFFFDFAF2), Color(0xFF1F292E), Color(0xFF8A8F94)),
    ReaderTheme("宣", Color(0xFFF1EAD4), Color(0xFF4A3F2E), Color(0xFFA39A82)),
    ReaderTheme("竹", Color(0xFFDDE9CC), Color(0xFF2E3D35), Color(0xFF7E958A)),
    ReaderTheme("夜", Color(0xFF10141A), Color(0xFFC6CCC9), Color(0xFF565D63))
)
