package io.lanread.app.source

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.lanread.app.data.AppDatabase
import io.lanread.app.data.BookEntity
import io.lanread.app.data.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.net.URLEncoder

/** 全局 JS 桥：java/cache/source 绑定单例（put/get 跨调用持久） */
object LanJs {
    val java: Analyzer.JsExt = Analyzer.JsExt { s ->
        Handler(Looper.getMainLooper()).post {
            LanJs.appCtx?.let { c ->
                Toast.makeText(c, s, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val cache: Analyzer.JsCache = Analyzer.JsCache()
    val source: Analyzer.JsSource = Analyzer.JsSource("")

    /** toast 回调需要 Context，首次使用时注入 */
    @Volatile
    var appCtx: Context? = null

    fun ensure(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    fun bindings(): Map<String, Any> = mapOf(
        "java" to java,
        "cache" to cache,
        "source" to source
    )
}

/** 书源导入 / 聚合搜索 / 目录 / 正文（含漫画源） */
class SourceRepository(private val db: AppDatabase, private val appCtx: Context? = null) {

    private val sourceDao get() = db.sourceDao()
    private val bookDao get() = db.bookDao()

    /** 最近一次空结果的原因（explorePage 写，UI 读拼提示） */
    @Volatile
    var lastEmptyReason: String? = null

    private val jsLibBindings: Map<String, Any>
        get() {
            appCtx?.let { LanJs.ensure(it) }
            return LanJs.bindings()
        }

    fun sourcesFlow() = sourceDao.all()

    data class ImportResult(val added: Int, val skipped: Int, val error: String?) {
        val ok: Boolean get() = error == null
        val summary: String
            get() = when {
                error != null -> error
                added > 0 -> "导入成功：新增 $added 条" + if (skipped > 0) "，跳过重复/无效 $skipped 条" else ""
                else -> "没有新增（重复或无效书源 $skipped 条）"
            }
    }

    suspend fun import(text: String): ImportResult = withContext(Dispatchers.IO) {
        val body = if (text.trim().startsWith("http://") || text.trim().startsWith("https://")) {
            runCatching { Http.get(text.trim()) }.getOrElse {
                return@withContext ImportResult(0, 0, "下载失败：${it.message}")
            }
        } else text
        val list = BookSourceParser.parseText(body)
        if (list.isEmpty()) {
            return@withContext ImportResult(0, 0, "未解析到书源（内容格式不符）")
        }
        var added = 0
        var skipped = 0
        for (s in list) {
            if (s.bookSourceUrl.isBlank() || s.bookSourceName.isBlank()) {
                skipped++; continue
            }
            if (sourceDao.countByUrl(s.bookSourceUrl) > 0) {
                skipped++; continue
            }
            sourceDao.insert(
                SourceEntity(
                    name = s.bookSourceName,
                    url = s.bookSourceUrl,
                    group = s.bookSourceGroup ?: "",
                    enabled = s.enabled ?: true,
                    json = BookSourceParser.json.encodeToString(BookSource.serializer(), s)
                )
            )
            added++
        }
        ImportResult(added, skipped, null)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        sourceDao.setEnabled(id, enabled)
    }

    suspend fun deleteSource(id: Long) = withContext(Dispatchers.IO) {
        sourceDao.delete(id)
    }

    // ---------------- 搜索 ----------------

    @Serializable
    data class SearchHit(
        val sourceId: Long,
        val sourceName: String,
        val title: String,
        val author: String,
        val bookUrl: String,
        val coverUrl: String = "",
        val latestChapter: String = "",
        val intro: String = "",
        val isComic: Boolean = false,    // bookSourceType == 2
        val kind: String = ""           // 连载状态/分类（如 "连载中$$韩漫"，$$ 分隔）
    )

    suspend fun searchAll(keyword: String): List<SearchHit> = withContext(Dispatchers.IO) {
        appCtx?.let { LanJs.ensure(it) }
        val sources = sourceDao.enabled()
        if (sources.isEmpty()) return@withContext emptyList()
        coroutineScope {
            sources.map { se ->
                async {
                    // 单源限时：书源自带 JS 探测线路时避免弱网下长时间卡搜索
                    runCatching { withTimeoutOrNull(90_000L) { searchOne(se, keyword) } }
                        .getOrElse { emptyList() } ?: emptyList()
                }
            }.awaitAll().flatten()
        }
    }

    private fun searchOne(se: SourceEntity, keyword: String): List<SearchHit> {
        val src = BookSourceParser.parseSingle(se.json) ?: return emptyList()
        val rules = src.ruleSearch ?: return emptyList()
        // Legado 标准把 searchUrl 放在顶层（ruleSearch.searchUrl 为旧兼容）
        val searchUrl = src.searchUrl ?: rules.searchUrl ?: return emptyList()
        val isComic = (src.bookSourceType ?: 0) == 2
        val srcHeaders = Http.parseHeaders(src.header)
        val keyEnc = URLEncoder.encode(keyword, "UTF-8")
        val tplVars = mapOf(
            "key" to keyEnc,
            "searchKey" to keyEnc,
            "page" to "1",
            "baseUrl" to se.url
        )
        val url = Analyzer.evalTemplate(
            searchUrl, Analyzer.Ctx(""), tplVars, src.jsLib, jsLibBindings
        )
        // Legado 约定：规则阶段的 baseUrl 是实际请求 URL
        // （漫蛙等源的 bookList 脚本从 baseUrl 里提取 k=/page= 参数）
        val vars = tplVars + mapOf("baseUrl" to url)
        // 模板化 URL（{{Url()}} 动态线路）请求失败不致命：bookList 的 JS 通常自行请求
        val body = runCatching { Http.get(url, se.url, srcHeaders) }.getOrElse { "" }
        // 列表规则（<js>$.list 两段式 / CSS / JSONPath）
        val items = Analyzer.getItems(body, se.url, rules.bookList, vars, src.jsLib, jsLibBindings)
        val hits = ArrayList<SearchHit>()
        for (item in items) {
            val name = Analyzer.getString(rules.name, item, vars, src.jsLib, jsLibBindings)?.trim()
            val bookUrl = Analyzer.getString(rules.bookUrl, item, vars, src.jsLib, jsLibBindings)?.trim()
            if (name.isNullOrBlank() || bookUrl.isNullOrBlank()) continue
            hits.add(
                SearchHit(
                    sourceId = se.id,
                    sourceName = se.name,
                    title = name,
                    author = Analyzer.getString(rules.author, item, vars, src.jsLib, jsLibBindings)?.trim() ?: "",
                    bookUrl = Http.absUrl(se.url, bookUrl),
                    coverUrl = Http.absUrl(se.url, Analyzer.getString(rules.coverUrl, item, vars, src.jsLib, jsLibBindings)?.trim() ?: ""),
                    latestChapter = Analyzer.getString(rules.lastChapter, item, vars, src.jsLib, jsLibBindings)?.trim() ?: "",
                    intro = Analyzer.getString(rules.intro, item, vars, src.jsLib, jsLibBindings)?.trim() ?: "",
                    kind = Analyzer.getString(rules.kind, item, vars, src.jsLib, jsLibBindings)?.trim() ?: "",
                    isComic = isComic
                )
            )
        }
        // 去重：同源同书重复出现时避免列表 key 冲突闪退（Compose 要求 key 唯一）
        return hits.distinctBy { it.sourceId to it.bookUrl }
    }

    /** 入书架结果：existed=true 表示书架上已有（返回既有书 id，未重复入库） */
    data class ShelfResult(val bookId: Long, val existed: Boolean)

    /** 将搜索/发现结果入库：同源+同 URL 已在书架则直接返回既有书（去重） */
    suspend fun addToShelf(hit: SearchHit): ShelfResult = withContext(Dispatchers.IO) {
        // 相对路径兜底补全：以库中书源地址为 base（防止漏网相对链接入书架后打不开）
        val baseUrl = sourceDao.byId(hit.sourceId)?.url
        val absUri = Http.absUrl(baseUrl, hit.bookUrl)
        val existing = bookDao.bySourceAndUri(hit.sourceId, absUri)
        if (existing != null) return@withContext ShelfResult(existing.id, existed = true)
        val id = bookDao.insert(
            BookEntity(
                title = hit.title,
                author = hit.author,
                type = if (hit.isComic) 2 else 1,
                uri = absUri,
                sourceId = hit.sourceId,
                sourceName = hit.sourceName,
                coverUrl = Http.absUrl(baseUrl, hit.coverUrl),
                intro = hit.intro,
                latestChapter = hit.latestChapter
            )
        )
        ShelfResult(id, existed = false)
    }

    // ---------------- 详情 / 目录 / 正文 ----------------

    // ---------------- 发现页 ----------------

    /** 发现页入口分组（exploreUrl 静态解析 或 @js: 动态执行） */
    suspend fun exploreEntries(sourceId: Long): List<ExploreItem> = withContext(Dispatchers.IO) {
        appCtx?.let { LanJs.ensure(it) }
        // 动态入口可能逐线探测网络（漫蛙 4 频道 × 5 线路），限时防 UI 长转圈
        withTimeoutOrNull(120_000L) {
        val se = sourceDao.byId(sourceId) ?: return@withTimeoutOrNull emptyList()
        val src = BookSourceParser.parseSingle(se.json) ?: return@withTimeoutOrNull emptyList()
        val raw = src.exploreUrl ?: return@withTimeoutOrNull emptyList()
        if (raw.trim().startsWith("@js:")) {
            // 动态标签：执行 JS（jsLib 前置），返回 JSON 数组
            val js = raw.trim().removePrefix("@js:")
            val ret = Analyzer.runJs(js, mapOf("baseUrl" to se.url, "page" to "1"), src.jsLib, jsLibBindings)
            if (ret.isNullOrBlank()) lastEmptyReason = "exploreUrl 脚本无返回（jsLib 执行失败或被沙箱拦截）"
            ExploreUrlParser.parseDynamicJson(ret)
        } else {
            ExploreUrlParser.parseStatic(raw)
        }
        } ?: emptyList()
    }

    /** 发现页某分组第 page 页的书籍列表（复用 ruleExplore 规则链） */
    suspend fun explorePage(
        sourceId: Long,
        entryUrlTemplate: String,
        page: Int
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        appCtx?.let { LanJs.ensure(it) }
        val se = sourceDao.byId(sourceId) ?: return@withContext emptyList()
        val src = BookSourceParser.parseSingle(se.json) ?: return@withContext emptyList()
        val rules = src.ruleExplore ?: return@withContext emptyList()
        val isComic = (src.bookSourceType ?: 0) == 2
        val srcHeaders = Http.parseHeaders(src.header)
        val tplVars = mapOf("page" to page.toString(), "baseUrl" to se.url)
        val url = Analyzer.evalTemplate(
            entryUrlTemplate, Analyzer.Ctx(""), tplVars, src.jsLib, jsLibBindings
        )
        // 同搜索：规则阶段的 baseUrl 为实际分页 URL（漫娃脚本从中提取 /api/… path 与 tag）
        val vars = tplVars + mapOf("baseUrl" to url)
        val body = try {
            Http.get(url, se.url, srcHeaders)
        } catch (e: Exception) {
            lastEmptyReason = "入口页请求失败（${e.message?.take(80) ?: e.javaClass.simpleName}），若列表规则含 JS 可自愈"
            ""
        }
        val items = Analyzer.getItems(body, se.url, rules.bookList, vars, src.jsLib, jsLibBindings)
        val hits = ArrayList<SearchHit>()
        for (item in items) {
            val name = Analyzer.getString(rules.name, item, vars, src.jsLib, jsLibBindings)?.trim()
            val bookUrl = Analyzer.getString(rules.bookUrl, item, vars, src.jsLib, jsLibBindings)?.trim()
            if (name.isNullOrBlank() || bookUrl.isNullOrBlank()) continue
            hits.add(
                SearchHit(
                    sourceId = se.id,
                    sourceName = se.name,
                    title = name,
                    author = Analyzer.getString(rules.author, item, vars, src.jsLib, jsLibBindings)?.trim() ?: "",
                    bookUrl = Http.absUrl(se.url, bookUrl),
                    coverUrl = Http.absUrl(se.url, Analyzer.getString(rules.coverUrl, item, vars, src.jsLib, jsLibBindings)?.trim() ?: ""),
                    latestChapter = Analyzer.getString(rules.lastChapter, item, vars, src.jsLib, jsLibBindings)?.trim() ?: "",
                    intro = Analyzer.getString(rules.intro, item, vars, src.jsLib, jsLibBindings)?.trim() ?: "",
                    kind = Analyzer.getString(rules.kind, item, vars, src.jsLib, jsLibBindings)?.trim() ?: "",
                    isComic = isComic
                )
            )
        }
        // 去重：同源同书重复出现时避免列表 key 冲突闪退（Compose 要求 key 唯一）
        hits.distinctBy { it.sourceId to it.bookUrl }
    }

    /** 发现页可用的启用书源（有 exploreUrl 且 enabledExplore 非 false） */
    suspend fun exploreSources(): List<SourceEntity> = withContext(Dispatchers.IO) {
        val all = sourceDao.enabled()
        all.filter { se ->
            val src = BookSourceParser.parseSingle(se.json)
            src?.exploreUrl?.isNotBlank() == true && (src.enabledExplore ?: true)
        }
    }

    data class BookInfo(
        val tocUrl: String,
        val intro: String,
        val coverUrl: String,
        val lastChapter: String
    )

    /** 详情页诊断：init 输出摘要（为空/异常时 UI 拼进"目录为空"提示，便于定位真机问题） */
    @Volatile
    var lastBookInfoNote: String? = null

    suspend fun bookInfo(sourceId: Long, bookUrl: String): BookInfo? = withContext(Dispatchers.IO) {
        appCtx?.let { LanJs.ensure(it) }
        val se = sourceDao.byId(sourceId) ?: return@withContext null
        val src = BookSourceParser.parseSingle(se.json) ?: return@withContext null
        val rules = src.ruleBookInfo ?: return@withContext null
        val vars = mapOf("baseUrl" to bookUrl, "page" to "1")
        val srcHeaders = Http.parseHeaders(src.header)
        lastBookInfoNote = null

        // init 规则：执行 JS 得 JSON（对象或字符串），作为字段求值上下文
        val initOut = if (!rules.init.isNullOrBlank()) {
            Analyzer.getString(rules.init, Analyzer.Ctx(""), vars, src.jsLib, jsLibBindings)
        } else null
        if (!rules.init.isNullOrBlank() && initOut.isNullOrBlank()) {
            lastBookInfoNote = "详情规则(init)无输出：jsLib 执行失败或线路不可达"
        }
        val ctx: Analyzer.Ctx = if (!initOut.isNullOrBlank()) {
            Analyzer.Ctx(initOut)
        } else {
            runCatching { Analyzer.Ctx(Jsoup.parse(Http.get(bookUrl, se.url, srcHeaders), bookUrl)) }
                .getOrElse { Analyzer.Ctx("") }
        }

        fun f(rule: String?): String =
            Analyzer.getString(rule, ctx, vars, src.jsLib, jsLibBindings)?.trim() ?: ""

        val info = BookInfo(
            tocUrl = f(rules.tocUrl).ifBlank { bookUrl },
            intro = f(rules.intro),
            coverUrl = f(rules.coverUrl),
            lastChapter = f(rules.lastChapter)
        )
        if (lastBookInfoNote == null && info.tocUrl.isBlank()) {
            lastBookInfoNote = "tocUrl 未提取到（回退详情 URL，目录脚本可能拿不到参数）"
        }
        info
    }

    data class Chapter(val index: Int, val title: String, val url: String)

    suspend fun toc(sourceId: Long, tocUrl: String): List<Chapter> = withContext(Dispatchers.IO) {
        appCtx?.let { LanJs.ensure(it) }
        val se = sourceDao.byId(sourceId) ?: return@withContext emptyList()
        val src = BookSourceParser.parseSingle(se.json) ?: return@withContext emptyList()
        val rules = src.ruleToc ?: return@withContext emptyList()
        val vars = mapOf("baseUrl" to tocUrl, "page" to "1")
        val srcHeaders = Http.parseHeaders(src.header)

        // chapterList 可能是 <js>...</js>\n$.list 两段式（不需要先抓页面）
        val chapterListRule = rules.chapterList ?: return@withContext emptyList()
        val items = if (chapterListRule.trim().startsWith("<js>")) {
            Analyzer.getItems("", tocUrl, chapterListRule, vars, src.jsLib, jsLibBindings)
        } else {
            val body = runCatching { Http.get(tocUrl, se.url, srcHeaders) }.getOrElse { "" }
            Analyzer.getItems(body, tocUrl, chapterListRule, vars, src.jsLib, jsLibBindings)
        }
        val list = ArrayList<Chapter>()
        for (item in items) {
            val cu = Analyzer.getString(rules.chapterUrl, item, vars, src.jsLib, jsLibBindings)?.trim()
            if (cu.isNullOrBlank()) continue
            val title = Analyzer.getString(rules.chapterName, item, vars, src.jsLib, jsLibBindings)?.trim()
                ?: "第${list.size + 1}节"
            list.add(Chapter(list.size, title, cu))
        }
        // 去重：章节数据含重复链接时避免列表 key 冲突闪退
        list.distinctBy { it.url }
    }

    /**
     * 正文：返回文本或 HTML。
     * 漫画源（<js> 产出 <img src=...> 串）原样返回，由阅读器分支渲染图片。
     */
    suspend fun content(sourceId: Long, chapterUrl: String): String = withContext(Dispatchers.IO) {
        appCtx?.let { LanJs.ensure(it) }
        val se = sourceDao.byId(sourceId) ?: return@withContext ""
        val src = BookSourceParser.parseSingle(se.json) ?: return@withContext ""
        val rule = src.ruleContent?.content ?: return@withContext ""
        val vars = mapOf("baseUrl" to chapterUrl, "page" to "1")
        val srcHeaders = Http.parseHeaders(src.header)

        val result: String? = if (rule.trim().startsWith("<js>")) {
            Analyzer.getString(rule, Analyzer.Ctx(""), vars, src.jsLib, jsLibBindings)
        } else {
            val body = runCatching { Http.get(chapterUrl, se.url, srcHeaders) }.getOrElse { "" }
            val ctx = if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
                Analyzer.Ctx(body)
            } else {
                Analyzer.Ctx(Jsoup.parse(body, chapterUrl))
            }
            Analyzer.getString(rule, ctx, vars, src.jsLib, jsLibBindings)
        }
        (result ?: "").replace("&nbsp;", " ").replace("&#160;", " ")
    }
}
