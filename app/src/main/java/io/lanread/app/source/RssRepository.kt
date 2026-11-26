package io.lanread.app.source

import android.content.Context
import io.lanread.app.data.AppDatabase
import io.lanread.app.data.RssSourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 订阅源仓库：导入 / 分组浏览 / 正文渲染 / 一键导入闭环 */
class RssRepository(
    private val db: AppDatabase,
    private val appCtx: Context,
    private val sourceRepository: SourceRepository
) {
    private val rssDao get() = db.rssSourceDao()

    /** 最近一次空页原因（loadGroup 写，UI 读拼提示） */
    @Volatile
    var lastEmptyReason: String? = null

    fun sourcesFlow() = rssDao.all()

    // ---------------- 导入 ----------------

    data class ImportResult(val added: Int, val skipped: Int, val error: String?) {
        val summary: String
            get() = when {
                error != null -> error
                added > 0 -> "导入成功：新增 $added 个订阅源" + if (skipped > 0) "，跳过重复/无效 $skipped 个" else ""
                else -> "没有新增（重复或无效订阅源 $skipped 个）"
            }
    }

    suspend fun import(text: String): ImportResult = withContext(Dispatchers.IO) {
        val body = if (text.trim().startsWith("http://") || text.trim().startsWith("https://")) {
            runCatching { Http.get(text.trim()) }.getOrElse {
                return@withContext ImportResult(0, 0, "下载失败：${it.message}")
            }
        } else text
        val list = RssSourceParser.parseText(body)
            .filter { it.sourceName.isNotBlank() && it.sourceUrl.isNotBlank() }
        if (list.isEmpty()) {
            // 防呆：书源 JSON 误导入订阅源（bookSourceUrl 为 Legado 书源独有标志）
            if (body.contains("bookSourceUrl")) {
                return@withContext ImportResult(0, 0, "这是书源（BookSource）格式，请到「书源」页导入")
            }
            return@withContext ImportResult(0, 0, "未解析到订阅源（内容不是 RssSource JSON）")
        }
        var added = 0
        var skipped = 0
        for (s in list) {
            if (rssDao.countByUrl(s.sourceUrl) > 0) {
                skipped++; continue
            }
            rssDao.insert(
                RssSourceEntity(
                    name = s.sourceName,
                    url = s.sourceUrl,
                    group = s.sourceGroup ?: "",
                    enabled = s.enabled ?: true,
                    json = RssSourceParser.json.encodeToString(RssSource.serializer(), s)
                )
            )
            added++
        }
        ImportResult(added, skipped, null)
    }

    suspend fun deleteSource(id: Long) = withContext(Dispatchers.IO) { rssDao.delete(id) }

    /**
     * legado://import/auto 闭环：src 指向一份 JSON——自动判别书源 / 订阅源入库。
     * 返回可读结果消息。
     */
    suspend fun importAuto(urlOrJson: String): String = withContext(Dispatchers.IO) {
        val text = if (urlOrJson.trim().startsWith("http")) {
            runCatching { Http.get(urlOrJson.trim()) }.getOrElse {
                return@withContext "一键导入失败：${it.message}"
            }
        } else urlOrJson

        // ① 先按书源判断（bookSourceUrl 为书源独有标志）
        if (text.contains("bookSourceUrl")) {
            val r = sourceRepository.import(text)
            if (r.error != null) return@withContext "书源导入失败：${r.error}"
            return@withContext if (r.added > 0) "书源已入库（新增 ${r.added} 条）"
            else "书源没有新增（重复 ${r.skipped} 条）"
        }
        // ② 再按订阅源判断
        val rr = import(text)
        if (rr.error != null) return@withContext "订阅源导入失败：${rr.error}"
        if (rr.added > 0) return@withContext "订阅源已入库（新增 ${rr.added} 个）"
        // ③ 都不是
        "无法识别的源类型（既非书源也非订阅源）"
    }

    // ---------------- 分组浏览 ----------------

    data class Article(
        val title: String,
        val link: String,
        val pubDate: String,
        val html: String          // 预渲染好的正文（articleStyle 决定）
    )

    /** 加载某源某分组第 page 页文章列表（含预渲染正文）
     * JS 桥走 LanJs 全局单例：与书源共享变量存（java.put/get）与 cache，
     * 订阅源的 source.getVariable() 即读全局变量。 */
    suspend fun loadGroup(
        sourceId: Long,
        groupIndex: Int,
        page: Int
    ): List<Article> = withContext(Dispatchers.IO) {
        LanJs.ensure(appCtx)
        val se = rssDao.byId(sourceId) ?: return@withContext emptyList()
        val src = RssSourceParser.parseSingle(se.json) ?: return@withContext emptyList()
        val groups = src.groups()
        if (groupIndex !in groups.indices) return@withContext emptyList()
        val g = groups[groupIndex]

        val vars = mapOf("page" to page.toString())
        val bindings = LanJs.bindings()
        val url = Analyzer.evalTemplate(g.urlTemplate, Analyzer.Ctx(""), vars, src.jsLib, bindings)

        lastEmptyReason = null
        val srcHeaders = Http.parseHeaders(src.header)
        val body = try {
            Http.get(url, se.url, srcHeaders)
        } catch (e: Exception) {
            lastEmptyReason = "站点不可达（${e.message?.take(80) ?: e.javaClass.simpleName}）"
            ""
        }
        val items = Analyzer.getItems(body, se.url, src.ruleArticles)
        val out = ArrayList<Article>(items.size)
        for (item in items) {
            val title = Analyzer.getString(src.ruleTitle, item, vars, src.jsLib, bindings)?.trim() ?: ""
            if (title.isBlank()) continue
            val link = Analyzer.getString(src.ruleLink, item, vars, src.jsLib, bindings)?.trim() ?: ""
            val pubDate = Analyzer.getString(src.rulePubDate, item, vars, src.jsLib, bindings)?.trim() ?: ""
            val html = renderArticle(src, item, vars, bindings)
            out.add(Article(title, link, pubDate, html))
        }
        out
    }

    /** 在列表项上下文渲染正文（ruleDescription 模板 + style 注入） */
    private fun renderArticle(
        src: RssSource,
        item: Analyzer.Ctx,
        vars: Map<String, Any?>,
        bindings: Map<String, Any?>
    ): String {
        val desc = src.ruleDescription?.let {
            Analyzer.getString(it, item, vars, src.jsLib, bindings)
        } ?: ""
        val css = src.style ?: ""
        val styleBlock = if (css.isBlank()) "body{padding:12px;font-size:15px;line-height:1.7;word-break:break-all;}"
        else "body{padding:12px;font-size:15px;line-height:1.7;word-break:break-all;}\n$css"
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>").append(styleBlock).append("</style></head><body>")
            append(desc.ifBlank { "<p>（正文规则未提取到内容）</p>" })
            append("</body></html>")
        }
    }
}
