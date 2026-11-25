package io.lanread.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * 澜阅视觉识别 —— 取自「澜」的水与中文书卷的纸墨：
 *  墨青 #1E3A47（主色，如砚池深水）
 *  澜水 #3E7C8F（辅色，波光；在线书标记）
 *  宣纸 #F6F3EA（页面底色）
 *  竹青 #4C7A5A（第三色；本地书标记）
 *  夜墨 #101A1F（暗色底）
 * 字体角色：标题=平台衬线（宋体族，克制使用）；正文=系统无衬线；页码等数字=等宽字。
 */
object LanType {
    val Display = FontFamily.Serif      // 标题：书卷衬线
    val Folio = FontFamily.Monospace    // 页码/计数：folio 数字
}

private val InkLight = lightColorScheme(
    primary = Color(0xFF1E3A47),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE0E4),
    onPrimaryContainer = Color(0xFF0F2833),
    secondary = Color(0xFF3E7C8F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E7EA),
    onSecondaryContainer = Color(0xFF11333D),
    tertiary = Color(0xFF4C7A5A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD8E8D0),
    onTertiaryContainer = Color(0xFF1E3B27),
    background = Color(0xFFF6F3EA),
    onBackground = Color(0xFF22292E),
    surface = Color(0xFFFCFBF6),
    onSurface = Color(0xFF22292E),
    surfaceVariant = Color(0xFFE9E4D6),
    onSurfaceVariant = Color(0xFF5C6168),
    outline = Color(0xFFD8D2C2),
    outlineVariant = Color(0xFFE5E0D0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val InkNight = darkColorScheme(
    primary = Color(0xFFA9C7CE),
    onPrimary = Color(0xFF0F2B33),
    primaryContainer = Color(0xFF2A4A55),
    onPrimaryContainer = Color(0xFFD3E8EC),
    secondary = Color(0xFF90BEC8),
    onSecondary = Color(0xFF12323C),
    secondaryContainer = Color(0xFF2E4E58),
    onSecondaryContainer = Color(0xFFBFD8DE),
    tertiary = Color(0xFFA9C9AB),
    onTertiary = Color(0xFF17351F),
    tertiaryContainer = Color(0xFF2F4D35),
    onTertiaryContainer = Color(0xFFC5E4C6),
    background = Color(0xFF101A1F),
    onBackground = Color(0xFFE2E4E2),
    surface = Color(0xFF141E24),
    onSurface = Color(0xFFE2E4E2),
    surfaceVariant = Color(0xFF3A4148),
    onSurfaceVariant = Color(0xFFBFC3C8),
    outline = Color(0xFF454A4F),
    outlineVariant = Color(0xFF3A4148),
    error = Color(0xFFE2C1BC),
    onError = Color(0xFF410E0B),
    errorContainer = Color(0xFF690005),
    onErrorContainer = Color(0xFFF9DEDC)
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
    ReaderTheme("纸", Color(0xFFFCFBF6), Color(0xFF22292E), Color(0xFF8A8F94)),
    ReaderTheme("宣", Color(0xFFF3EDDA), Color(0xFF4A3F2E), Color(0xFFA39A82)),
    ReaderTheme("竹", Color(0xFFE1ECD2), Color(0xFF2E3D35), Color(0xFF7E958A)),
    ReaderTheme("夜", Color(0xFF141819), Color(0xFFC9CDC9), Color(0xFF5C6265))
)
