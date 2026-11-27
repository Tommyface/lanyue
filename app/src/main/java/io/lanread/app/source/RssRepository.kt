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
        fetchAndBuild(se, src, url, vars, bindings)
    }

    /**
     * v0.4.21 订阅源搜索：按源 searchUrl 模板（注入 key/searchKey/变量）请求 + 复用解析链。
     * 933 型 `/vodsearch/...?wd={{String(source.variable)}}`：source 绑临时 JsSource（variable=关键词），
     * 不碰全局变量存；yck 型 `{{key}}` 直取 vars。无 searchUrl 的源返回 null（UI 提示）。
     */
    suspend fun searchSource(
        sourceId: Long,
        keyword: String,
        page: Int = 1
    ): List<Article>? = withContext(Dispatchers.IO) {
        LanJs.ensure(appCtx)
        val se = rssDao.byId(sourceId) ?: return@withContext null
        val src = RssSourceParser.parseSingle(se.json) ?: return@withContext null
        val tpl = src.searchUrl ?: return@withContext null
        if (keyword.isBlank()) return@withContext null

        val vars = mapOf(
            "key" to keyword,
            "searchKey" to keyword,
            "page" to page.toString(),
            "baseUrl" to se.url,
            "sourceKey" to keyword
        )
        // source 绑临时桥：variable=关键词（{{source.variable}} 型模板），写入不影响全局存
        val bindings = mapOf(
            "java" to LanJs.java,
            "cache" to LanJs.cache,
            "source" to Analyzer.JsSource(keyword)
        )
        val url = Analyzer.evalTemplate(tpl, Analyzer.Ctx(""), vars, src.jsLib, bindings)
        if (url.isBlank() || !url.startsWith("http") && !url.startsWith("/")) {
            lastEmptyReason = "searchUrl 模板未生成有效 URL"
            return@withContext emptyList()
        }
        fetchAndBuild(se, src, url, vars, bindings)
    }

    /** 公共链路（v0.4.21 抽出）：请求 → ruleArticles 解析 → 0 条时镜像降级重试 → buildArticles */
    private fun fetchAndBuild(
        se: io.lanread.app.data.RssSourceEntity,
        src: RssSource,
        url: String,
        vars: Map<String, Any?>,
        bindings: Map<String, Any?>
    ): List<Article> {
        lastEmptyReason = null
        val srcHeaders = Http.parseHeaders(src.header)
        val body = try {
            Http.get(url, se.url, srcHeaders)
        } catch (e: Exception) {
            lastEmptyReason = "站点不可达（${e.message?.take(80) ?: e.javaClass.simpleName}）"
            ""
        }
        // baseUri 用实际请求 URL（bhost 活镜像）：旧版用 se.url（yck 源里是死域名 yckgo.top），
        // 相对 href 经 absUrl 拼出死链 → 一键导入 URL 残缺/不可达
        var items = Analyzer.getItems(body, url, src.ruleArticles)

        // 镜像降级重试（v0.4.13）：部分镜像 HEAD 200 伪活但 GET 深路径回壳页（实测 yck.email），
        // 解析 0 条时从 jsLib 提取候选域名逐个替换 host 重试，出内容即用
        if (items.isEmpty() && body.isNotBlank()) {
            val mirrorHosts = Regex("https?://([A-Za-z0-9.-]+)")
                .findAll(src.jsLib ?: "")
                .map { it.groupValues[1] }
                .filter { it != UriHost(url) }
                .distinct()
                .take(5)
            for (mh in mirrorHosts) {
                val altUrl = swapHost(url, mh) ?: continue
                val altBody = runCatching { Http.get(altUrl, se.url, srcHeaders) }.getOrNull() ?: continue
                if (altBody.length > body.length) {
                    val altItems = Analyzer.getItems(altBody, altUrl, src.ruleArticles)
                    if (altItems.isNotEmpty()) {
                        // bhost() 缓存纠正（v0.4.14）：重试成功的镜像写回 JS cache 的
                        // working_host，renderArticle 里 {{bhost()}} 生成的一键导入
                        // URL 才会指向真正服务内容的镜像（而非伪活的那台）
                        runCatching {
                            (bindings["cache"] as? Analyzer.JsCache)?.let { c ->
                                c.putMemory("working_host", "https://$mh")
                                c.putMemory(
                                    "host_expire",
                                    (System.currentTimeMillis() + 300000L).toString()
                                )
                            }
                        }
                        return buildArticles(src, altItems, altUrl, vars, bindings)
                    }
                }
            }
        }
        // v0.4.24：singleUrl 型源兜底——源没有 ruleArticles（煎蛋/优设/Acg17 等整站单页形态），
        // Legado 语义是源首页即一篇内容（WebView 渲染原网页）。列表产单篇「打开源网页」，
        // 文章页正文为空时 WebView 直接加载原页（RssArticleScreen 现有行为）。
        if (items.isEmpty() && (src.singleUrl == true || src.ruleArticles.isNullOrBlank())) {
            val title = src.sourceName.ifBlank { "源网页" }
            if (url.isNotBlank() && url.startsWith("http")) {
                lastEmptyReason = "singleUrl 源：直接打开原网页"
                return listOf(
                    Article(title, url, "", "<p>（singleUrl 源——点击将打开原网页）</p>")
                )
            }
        }
        return buildArticles(src, items, url, vars, bindings)
    }

    private fun UriHost(u: String): String =
        Regex("https?://([A-Za-z0-9.-]+)").find(u)?.groupValues?.get(1) ?: ""

    /** 把 URL 的 host 换成指定域名（scheme 保持原样） */
    private fun swapHost(u: String, host: String): String? {
        val m = Regex("(https?://)([A-Za-z0-9.-]+)(/.*)?").find(u) ?: return null
        return m.groupValues[1] + host + m.groupValues[3]
    }

    private fun buildArticles(
        src: RssSource,
        items: List<Analyzer.Ctx>,
        baseUri: String,
        vars: Map<String, Any?>,
        bindings: Map<String, Any?>
    ): List<Article> {
        val out = ArrayList<Article>(items.size)
        for (item in items) {
            val title = Analyzer.getString(src.ruleTitle, item, vars, src.jsLib, bindings)?.trim() ?: ""
            if (title.isBlank()) continue
            val link = Analyzer.getString(src.ruleLink, item, vars, src.jsLib, bindings)?.trim() ?: ""
            val pubDate = Analyzer.getString(src.rulePubDate, item, vars, src.jsLib, bindings)?.trim() ?: ""
            val html = renderArticle(src, item, vars, bindings)
            out.add(Article(title, link, pubDate, html))
        }
        return out
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

    // ---------------- v0.4.20 视频订阅源 ----------------

    /**
     * 视频详情页内容（ruleContent）：拉取条目原网页 HTML，在其上下文执行 ruleContent
     * （视频源自绘播放器页，如 933 国产黑料：hls.js 播放器 + source.get 轮询注入）。
     * 失败回退普通 WebView 直接加载原网页。
     */
    suspend fun videoArticle(sourceId: Long, link: String): String? = withContext(Dispatchers.IO) {
        LanJs.ensure(appCtx)
        val se = rssDao.byId(sourceId) ?: return@withContext null
        val src = RssSourceParser.parseSingle(se.json) ?: return@withContext null
        val rule = src.ruleContent ?: return@withContext null
        val headers = Http.parseHeaders(src.header)
        val absLink = Http.absUrl(se.url, link)
        val body = runCatching { Http.get(absLink, se.url, headers) }.getOrNull() ?: return@withContext null
        val vars = mapOf("baseUrl" to absLink, "page" to "1", "result" to body)
        // ruleContent 整段执行（@js:/<js> 短路已内置），result 变量注入原网页 HTML
        val html = Analyzer.getString(rule, Analyzer.Ctx(body), vars, src.jsLib, LanJs.bindings())
        html?.takeIf { it.isNotBlank() }
    }

    /** 视频列表条目封面（ruleImage）：Article 无封面字段，暂供 UI 后续扩展 */
    fun articleImageRule(src: RssSource): String? = src.ruleImage
}
