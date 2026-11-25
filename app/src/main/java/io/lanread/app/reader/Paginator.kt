package io.lanread.app.reader

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * 文本静态分页引擎：按段落贪心填充页面。
 * 段落布局基于 substring 构建（0 基偏移，无绝对/相对歧义），
 * 行末偏移换算为全文字符索引由调用处 segStart + rel 完成。
 */
object Paginator {

    data class Page(val start: Int, val end: Int)

    fun paginate(
        text: String,
        widthPx: Int,
        heightPx: Int,
        paint: TextPaint,
        lineSpacingMult: Float
    ): List<Page> {
        if (text.isEmpty()) return listOf(Page(0, 0))
        if (widthPx <= 0 || heightPx <= 0) return listOf(Page(0, text.length))
        val pages = ArrayList<Page>()
        val n = text.length
        var pos = 0
        while (pos < n) {
            var used = 0
            val pageStart = pos
            var lastGood = pos
            var segStart = pos
            while (segStart < n && used < heightPx) {
                val nl = text.indexOf('\n', segStart)
                val segEnd = if (nl == -1) n else nl + 1
                val slice = text.substring(segStart, segEnd)
                val layout = StaticLayout.Builder
                    .obtain(slice, 0, slice.length, paint, widthPx)
                    .setLineSpacing(0f, lineSpacingMult)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                val h = layout.height
                if (used + h <= heightPx) {
                    used += h
                    lastGood = segEnd
                    segStart = segEnd
                } else {
                    // 本段装不下：尝试装下前几行
                    val lineCount = layout.lineCount
                    if (lineCount > 0) {
                        val lineH = h.toFloat() / lineCount
                        val fit = ((heightPx - used) / lineH).toInt()
                        if (fit > 0) {
                            // getLineEnd 相对 slice 起点（0 基），换算为全文绝对索引
                            lastGood = segStart + layout.getLineEnd(minOf(fit, lineCount) - 1)
                        }
                    }
                    break
                }
            }
            val pageEnd = if (lastGood > pageStart) lastGood else minOf(pageStart + 1, n)
            pages.add(Page(pageStart, pageEnd))
            pos = pageEnd
        }
        return pages
    }

    fun pageOf(pages: List<Page>, charOffset: Int): Int {
        for ((i, p) in pages.withIndex()) {
            if (charOffset < p.end) return i
        }
        return (pages.size - 1).coerceAtLeast(0)
    }
}
