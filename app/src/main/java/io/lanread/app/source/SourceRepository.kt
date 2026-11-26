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
import java.io.File

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
        // key 传原始关键词（Legado 语义）：JS 脚本内部 String(key) 需要原始值自行编码
        // （OmoFun 等源 encodeURIComponent(String(key))——旧版预编码会导致双重编码搜索失败）；
        // 模板 {{key}} 的替换由 evalTemplate 特判做百分号编码
        // host：书源首页（墨阅等源的 <js> URL 拼接用 host + '/legado/search...'）
        val tplVars = mapOf(
            "key" to keyword,
            "searchKey" to keyword,
            "page" to "1",
            "baseUrl" to se.url,
            "host" to se.url
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
            val jsErr = Analyzer.lastJsError
            lastBookInfoNote = if (jsErr != null) "详情规则(init)异常：$jsErr"
            else "详情规则(init)返回空（多为手机网络无法直达源线路，可到书源页-源变量/登录页切换线路后重试）"
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
     * v0.4.18：支持 ruleContent.nextContentUrl 自动翻页——正文分页时逐页抓取拼接
     * （漫画源每页若干图、文本源每页一段，均常见），最多 10 页防失控。
     */
    suspend fun content(sourceId: Long, chapterUrl: String): String = withContext(Dispatchers.IO) {
        appCtx?.let { LanJs.ensure(it) }
        val se = sourceDao.byId(sourceId) ?: return@withContext ""
        val src = BookSourceParser.parseSingle(se.json) ?: return@withContext ""
        val rule = src.ruleContent?.content ?: return@withContext ""
        val nextRule = src.ruleContent?.nextContentUrl
        val srcHeaders = Http.parseHeaders(src.header)

        var currentUrl = chapterUrl
        val parts = ArrayList<String>()
        var visited = HashSet<String>()
        var pageCount = 0
        while (pageCount < 10 && !visited.contains(currentUrl)) {
            visited.add(currentUrl)
            pageCount++
            val vars = mapOf("baseUrl" to currentUrl, "page" to pageCount.toString())
            val (partHtml, nextUrl) = fetchOnePage(src, rule, nextRule, currentUrl, se.url, srcHeaders, vars)
            if (partHtml.isNotBlank()) parts.add(partHtml)
            if (nextUrl.isNullOrBlank() || nextUrl == currentUrl) break
            currentUrl = Http.absUrl(currentUrl, nextUrl)
        }
        parts.joinToString("\n").replace("&nbsp;", " ").replace("&#160;", " ")
    }

    /** 抓单页正文 + 提取下一页链接（nextContentUrl 规则，相对链接按当前页补全） */
    private fun fetchOnePage(
        src: BookSource,
        rule: String,
        nextRule: String?,
        currentUrl: String,
        srcHome: String,
        srcHeaders: Map<String, String>?,
        vars: Map<String, Any?>
    ): Pair<String, String?> {
        val jsLib = src.jsLib
        val bindings = jsLibBindings
        val partHtml: String?
        var nextPage: String? = null
        if (rule.trim().startsWith("<js>")) {
            partHtml = Analyzer.getString(rule, Analyzer.Ctx(""), vars, jsLib, bindings)
        } else {
            val body = runCatching { Http.get(currentUrl, srcHome, srcHeaders) }.getOrElse { "" }
            val ctx = if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
                Analyzer.Ctx(body)
            } else {
                Analyzer.Ctx(Jsoup.parse(body, currentUrl))
            }
            partHtml = Analyzer.getString(rule, ctx, vars, jsLib, bindings)
        }
        if (!nextRule.isNullOrBlank()) {
            // nextContentUrl 在当前页 body 上提取（文本/漫画通用），失败即无下一页
            nextPage = try {
                val body2 = runCatching { Http.get(currentUrl, srcHome, srcHeaders) }.getOrElse { "" }
                val ctx2 = if (body2.trimStart().startsWith("{") || body2.trimStart().startsWith("[")) {
                    Analyzer.Ctx(body2)
                } else {
                    Analyzer.Ctx(Jsoup.parse(body2, currentUrl))
                }
                Analyzer.getString(nextRule, ctx2, vars, jsLib, bindings)?.trim()
            } catch (e: Exception) {
                null
            }
        }
        return (partHtml ?: "") to nextPage
    }

    /**
     * v0.4.19 漫画图片解密管线：对正文 <img> 列表逐图
     * 下载（图片专用 OkHttp：UA/自签证书/HTTP1.1）→ 执行 imageDecode JS（result=图片字节）→
     * 解密结果写临时文件，返回 file:// URL 列表（未配置 imageDecode 或解密失败回退原始 URL）。
     */
    suspend fun decryptImages(sourceId: Long, contentHtml: String): List<String> = withContext(Dispatchers.IO) {
        appCtx?.let { LanJs.ensure(it) }
        val se = sourceDao.byId(sourceId) ?: return@withContext emptyList()
        val src = BookSourceParser.parseSingle(se.json) ?: return@withContext emptyList()
        val decodeRule = src.ruleContent?.imageDecode ?: return@withContext emptyList()
        val urls = ArrayList<String>()
        for (m in Regex("""<img[^>]+src=["']([^"']+)["']""").findAll(contentHtml)) {
            urls.add(m.groupValues[1])
        }
        val out = ArrayList<String>(urls.size)
        val tmpDir = File(appCtx?.cacheDir, "comic-decrypt").apply { mkdirs() }
        for ((i, u) in urls.withIndex()) {
            // 下载原始字节
            val bytes = runCatching {
                imageOkHttp(appCtx).newCall(
                    okhttp3.Request.Builder().url(Http.absUrl(se.url, u))
                        .header("User-Agent", Http.UA).header("Referer", se.url).build()
                ).execute().use { r -> r.body?.bytes() }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                out.add(u)   // 下载失败：回退原始 URL（Coil 直接试）
                continue
            }
            // imageDecode JS：result = 图片字节（Base64 可读形式传入）
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val decoded: ByteArray? = runCatching {
                Analyzer.getString(
                    decodeRule, Analyzer.Ctx(""), mapOf("baseUrl" to u),
                    src.jsLib, jsLibBindings + mapOf("result" to b64)
                )?.let { s ->
                    if (s.isBlank()) null
                    else android.util.Base64.decode(s.trim(), android.util.Base64.DEFAULT)
                }
            }.getOrNull()
            // JS 失败 → 原样（未解密）字节也落盘（比远程 URL 显示更稳）；解密成功 → 解密后字节
            val final = decoded ?: bytes
            val f = File(tmpDir, "img-${System.currentTimeMillis()}-$i.img")
            runCatching { f.writeBytes(final) }
            out.add(if (f.exists()) "file://${f.absolutePath}" else u)
        }
        out
    }

    /** 图片专用 OkHttp：与 LanApp 的 Coil 加载器同口径（自签证书放行 + HTTP/1.1） */
    private fun imageOkHttp(ctx: Context?): okhttp3.OkHttpClient {
        return okhttp3.OkHttpClient.Builder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .sslSocketFactory(looseSsl().first, looseSsl().second)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun looseSsl(): Pair<javax.net.ssl.SSLSocketFactory, javax.net.ssl.X509TrustManager> {
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
        val ssl = javax.net.ssl.SSLContext.getInstance("SSL")
        ssl.init(null, arrayOf<javax.net.ssl.TrustManager>(tm), java.security.SecureRandom())
        return ssl.socketFactory to tm
    }
}
