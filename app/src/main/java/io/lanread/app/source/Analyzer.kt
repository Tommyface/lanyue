package io.lanread.app.source

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.util.concurrent.ConcurrentHashMap

/**
 * Legado 规则求值引擎（兼容子集）—— 书源 + 订阅源 + 漫画源共用。
 *
 * 支持：
 *  - @css: 前缀 CSS 选择器（含 @attr 后缀）
 *  - $. / @json: / [ 开头的 JSONPath；JSON 对象上下文的无前缀字段名取值
 *  - @js: 与 {{...}} 模板（内容自动分流：规则表达式 / JS 脚本）
 *  - <js>...</js> 两段式规则：先执行 JS，返回值（JSON 字符串）作为新上下文，
 *    再求值剩余部分（如 "$.list"）
 *  - class.xxx / tag.xxx / id.xxx + 数字索引（含负索引 span.-1）+ @attr
 *  - 无前缀 token 的标签名识别（a/p/span/h2 直接按标签）
 *  - 规则末尾 ##regex[##replace] 净化；&& 并列 / || 兜底
 *  - jsLib 全局库注入 + java/cache/source 绑定（toast/put/get 变量存储）
 *  - baseUrl 变量注入（init/content 等规则的 JS 可读取当前页 URL）
 *  - ClassShutter 沙箱（jsoup / java 基础类 / crypto / net / io）
 * 暂不支持：XPath（// 开头）、对象型复合规则、POST 搜索
 */
object Analyzer {

    /** 最近一次 JS 执行异常（诊断透出用；每次 evalJs 开场清零） */
    @Volatile
    var lastJsError: String? = null

    /** 求值上下文：jsoup Element / Document，或 JSON 反序列化后的 Map/List/字符串 */
    class Ctx(val value: Any?) {
        fun asElement(): Element? = when (value) {
            is Element -> value
            is Document -> value.body()
            else -> null
        }
    }

    private val jsonConf = Configuration.builder()
        .options(Option.SUPPRESS_EXCEPTIONS)
        .build()

    /** jsLib 里的 java.* 扩展：toast 与 put/get 源变量存储（跨调用持久） */
    class JsExt(
        private val store: MutableMap<String, String?> = ConcurrentHashMap(),
        private val toastFn: (String) -> Unit = {}
    ) {
        fun toast(s: String?) {
            if (s != null) toastFn(s)
        }

        fun put(k: String?, v: String?) {
            if (k != null) store[k] = v
        }

        fun get(k: String?): String? = k?.let { store[it] }

        /** 源变量编辑 UI 用：全部变量快照 */
        fun all(): Map<String, String?> = store.toMap()

        fun remove(k: String?) {
            if (k != null) store.remove(k)
        }
    }

    class JsCache {
        private val mem = ConcurrentHashMap<String, Any?>()
        fun getFromMemory(key: String?): Any? = key?.let { mem[it] }
        fun putMemory(key: String?, value: Any?) {
            if (key != null) mem[key] = value
        }
    }

    class JsSource(
        private val variable: String,
        private val store: MutableMap<String, String?> = ConcurrentHashMap()
    ) {
        /** source.getVariable()：优先读 setVariable 写入的值（变量桥接语义） */
        fun getVariable(): String = store["variable"] ?: variable
        fun setVariable(v: String) { store["variable"] = v }
        fun put(k: String?, v: String?) {
            if (k != null) store[k] = v
        }
        fun get(k: String?): String? = k?.let { store[it] }
    }

    /**
     * JS 可见类控制（黑名单制，v0.4.8）。
     * 前缀白名单注定枚举不完 Android 系统实现：真机上 HttpURLConnection 的实现类是
     * com.android.okhttp.*（不是 okhttp3.*），TLS 握手在 conscrypt，socket 读写走
     * libcore.io.Posix——都不是 java./javax. 前缀，白名单一漏网络就全挂。
     * 书源 JS 由用户自行导入（README 已声明风险自担），因此只拒绝明确的进程级危险类：
     */
    private object LanShutter : ClassShutter {
        private val denyExact = setOf(
            "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Process",
            "java.lang.Shutdown", "java.lang.ApplicationShutdownHooks",
            "java.lang.ClassLoader", "java.lang.SecurityManager"
        )
        override fun visibleToScripts(fullClassName: String): Boolean = when {
            fullClassName in denyExact -> false
            fullClassName.startsWith("dalvik.system.") -> false   // DexClassLoader 动态加载代码
            else -> true
        }
    }

    // ---------------- 列表规则（bookList / chapterList / ruleArticles） ----------------

    fun getItems(
        body: String,
        baseUri: String,
        rule: String?,
        vars: Map<String, Any?> = emptyMap(),
        jsLib: String? = null,
        bindings: Map<String, Any?> = emptyMap()
    ): List<Ctx> {
        if (rule.isNullOrBlank()) return emptyList()
        val r = rule.trim()
        return try {
            when {
                r.startsWith("@js:") -> {
                    // 整段 JS 列表规则（v0.4.15）：OmoFun 等源 bookList 为 @js:(function(){...})()
                    // 返回值可能是 List（JS Array 或 java.util.ArrayList）/ JSON 数组字符串 / 单对象
                    val raw = evalJsRaw(r.removePrefix("@js:"), Ctx(body), vars, jsLib, bindings)
                    when (raw) {
                        is List<*> -> raw.map { Ctx(it) }
                        else -> {
                            val s = raw?.let { Context.toString(it) } ?: return emptyList()
                            if (s.isBlank()) emptyList()
                            else {
                                val v = readJson(Ctx(s), "$")?.value
                                when (v) {
                                    is List<*> -> v.map { Ctx(it) }
                                    else -> listOf(Ctx(s))
                                }
                            }
                        }
                    }
                }
                r.startsWith("<js>") -> {
                    // 两段式：<js>脚本</js> 后半段（如 $.list）
                    val end = r.indexOf("</js>")
                    if (end < 0) return emptyList()
                    val js = r.substring(4, end)
                    val rest = r.substring(end + 5).trim()
                    val jsResult = evalJs(js, Ctx(body), vars, jsLib, bindings)
                    if (jsResult.isNullOrBlank()) return emptyList()
                    if (rest.isBlank()) {
                        // 脚本本身返回列表 JSON（如 [{"title":..}]）
                        val v = readJson(Ctx(jsResult), "$")?.value
                        if (v is List<*>) v.map { Ctx(it) } else emptyList()
                    } else {
                        val v = readJson(Ctx(jsResult), rest)?.value
                        if (v is List<*>) v.map { Ctx(it) } else emptyList()
                    }
                }
                r.startsWith("@css:") -> {
                    val sel = r.removePrefix("@css:").substringBefore("@").trim()
                    selectItems(body, baseUri, sel)
                }
                r.startsWith("$.") || r.startsWith("@json:") || r.startsWith("[") -> {
                    val path = if (r.startsWith("@json:")) r.removePrefix("@json:") else r
                    val res = readJson(Ctx(body), path)
                    val v = res?.value
                    if (v is List<*>) v.map { Ctx(it) } else emptyList()
                }
                r.startsWith("//") -> emptyList() // XPath 暂不支持
                else -> {
                    // 中缀 @=子选择（Legado 语义 ".layui-card-body@.ylist"= 卡片下的 .ylist 条目）：
                    // 第二段以 . 开头或为常见标签 → 层级 CSS；末尾 @href 等 attr 词仍走属性提取。
                    // 旧版丢掉 @ 后段，列表项错位为整卡 → 条目内规则（a@href 等）提取落空。
                    val at = r.indexOf('@')
                    val descendant = at > 0 && r.substring(at + 1).let { seg ->
                        (seg.startsWith(".") || seg.startsWith("#") || HTML_TAGS.contains(seg.substringBefore('.').trim()))
                    }
                    val sel = if (descendant) {
                        r.split('@').filter { it.isNotBlank() }
                            .joinToString(" ") { splitSelector(it).component1() }
                    } else r.substringBefore("@").trim()
                    selectItems(body, baseUri, splitSelector(sel).component1())
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun selectItems(body: String, baseUri: String, css: String): List<Ctx> {
        val els = Jsoup.parse(body, baseUri).select(css)
        val out = ArrayList<Ctx>(els.size)
        for (e in els) out.add(Ctx(e))
        return out
    }

    // ---------------- 字符串字段规则 ----------------

    fun getString(
        rule: String?,
        ctx: Ctx,
        vars: Map<String, Any?> = emptyMap(),
        jsLib: String? = null,
        bindings: Map<String, Any?> = emptyMap()
    ): String? {
        if (rule.isNullOrBlank()) return null
        val r = rule.trim()
        // 整段 JS 规则（v0.4.16）：跳过 ||/&&/## 切分与 {{ 模板分流。
        // JS 代码里遍地是 ||（String(result||'')、j.data||j.list）——一切分脚本就碎成残段，
        // Rhino 解析报 EvaluatorException: missing ) after argument list（真机实测根因）。
        // 漫蛙 <js>…</js> 与 OmoFun @js:(function(){…})() 的 init 均为此形态。
        if (r.startsWith("<js>") || r.startsWith("@js:")) {
            return try {
                evalMain(r, ctx, vars, jsLib, bindings)
            } catch (e: Exception) {
                null
            }
        }
        if (r.contains("{{") && r.contains("}}")) {
            val tpl = evalTemplate(r, ctx, vars, jsLib, bindings)
            if (tpl.isNotBlank()) return tpl
        }
        if (r.contains("||")) {
            for (p in r.split("||")) {
                getString(p, ctx, vars, jsLib, bindings)?.let { return it }
            }
            return null
        }
        if (r.contains("&&")) {
            return r.split("&&")
                .mapNotNull { getString(it, ctx, vars, jsLib, bindings) }
                .joinToString("\n")
        }
        var main = r
        var filter: String? = null
        val hi = r.indexOf("##")
        if (hi >= 0) {
            main = r.substring(0, hi)
            filter = r.substring(hi + 2)
        }
        return try {
            val value = evalMain(main, ctx, vars, jsLib, bindings)
            if (value != null && filter != null) applyFilter(value, filter) else value
        } catch (e: Exception) {
            null
        }
    }

    private fun evalMain(
        r: String,
        ctx: Ctx,
        vars: Map<String, Any?>,
        jsLib: String?,
        bindings: Map<String, Any?>
    ): String? {
        if (r.isBlank()) return null
        return when {
            r.startsWith("<js>") -> {
                // 两段式：JS 结果作为新上下文，再求值剩余规则
                val end = r.indexOf("</js>")
                if (end < 0) return null
                val js = r.substring(4, end)
                val rest = r.substring(end + 5).trim()
                val jsResult = evalJs(js, ctx, vars, jsLib, bindings)
                if (jsResult == null) null
                else if (rest.isBlank()) jsResult
                else getString(rest, Ctx(jsResult), vars, jsLib, bindings)
            }
            r.startsWith("@js:") -> evalJs(r.removePrefix("@js:"), ctx, vars, jsLib, bindings)
            r.startsWith("$.") || r.startsWith("@json:") -> {
                val path = if (r.startsWith("@json:")) r.removePrefix("@json:") else r
                readJson(ctx, path)?.stringified()
            }
            r.startsWith("//") -> null
            r.startsWith("@css:") -> {
                val body = r.removePrefix("@css:")
                val root = ctx.asElement() ?: return null
                val (css, index) = splitSelector(body)
                selectByCss(root, css, index, attrOf(body))
            }
            else -> {
                // 前导 @ 容错（v0.4.14）：Legado 规则 `@a@href` / `@span.-2@text`（yck 订阅源
                // ruleDescription/rulePubDate 的实用写法）此前 splitSelector 截到空前缀 → css 为空
                // → 提取 null → 一键导入 URL 残缺（/yuedu//json/id/.json）与时间行空白。
                // @js:/@css:/@json: 已在前面分支处理，此处剩余前导 @ 一律剥离。
                val rr = if (r.startsWith("@")) r.substring(1) else r
                val v = ctx.value
                // JSON 字符串上下文（如 ruleBookInfo.init 的 JS 返回值）：
                // 裸字段名按 $.key 取，否则 name/author/tocUrl 等全部提取失败（v0.4.10 修复）
                val jsonLike = v is String && v.trimStart().let { it.startsWith("{") || it.startsWith("[") }
                val root = if (jsonLike) null else ctx.asElement()
                if (root != null) {
                    val (css, index) = splitSelector(rr)
                    selectByCss(root, css, index, attrOf(rr))
                } else if (v is Map<*, *> || v is List<*> || jsonLike) {
                    readJson(ctx, "$." + rr)?.stringified()
                } else null
            }
        }
    }

    // ---------------- {{...}} 模板 ----------------

    /** 对外脚本执行入口（登录页 action 等独立脚本场景）：loginUrl 作前置库 */
    fun runJs(
        code: String,
        vars: Map<String, Any?> = emptyMap(),
        jsLib: String? = null,
        bindings: Map<String, Any?> = emptyMap()
    ): String? = evalJs(code, Ctx(""), vars, jsLib, bindings)

    /** 旧签名（书源搜索 URL 等）：无 jsLib / bindings */
    fun evalTemplate(template: String, vars: Map<String, Any?>): String =
        evalTemplate(template, Ctx(""), vars, null, emptyMap())

    /**
     * 模板求值：{{...}} 内容自动分流——
     *   @xxx / $.xxx / .xxx / //xxx / [ 开头 → 规则表达式（getString）
     *   key/searchKey → URL 编码值（Legado 模板语义；JS 脚本内部的 key 变量则是原始值）
     *   其余 → JS 脚本（可带 jsLib 与绑定）
     */
    fun evalTemplate(
        template: String,
        ctx: Ctx,
        vars: Map<String, Any?>,
        jsLib: String?,
        bindings: Map<String, Any?>
    ): String {
        // 整段 JS URL（v0.4.17）：searchUrl 形如 <js>...host+'/legado/search?key='+encodeURIComponent(key)...</js>
        // （墨阅·起点等源）——无 {{}} 也需执行 JS；注意切分前短路（JS 内 || 会被模板字符扫描忽略，但保险起见先判前缀）
        val t = template.trim()
        if (t.startsWith("<js>")) {
            val end = t.indexOf("</js>")
            val code = if (end > 0) t.substring(4, end) else t.removePrefix("<js>")
            val ret = evalJs(code.trim(), ctx, vars, jsLib, bindings) ?: return template
            return ret
        }
        if (t.startsWith("@js:")) {
            val ret = evalJs(t.removePrefix("@js:"), ctx, vars, jsLib, bindings) ?: return template
            return ret
        }
        if (!template.contains("{{")) return template
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val s = template.indexOf("{{", i)
            if (s < 0) {
                sb.append(template.substring(i)); break
            }
            sb.append(template.substring(i, s))
            val e = template.indexOf("}}", s + 2)
            if (e < 0) {
                sb.append(template.substring(s)); break
            }
            val code = template.substring(s + 2, e)
            val v: String? = when {
                code.startsWith("@") || code.startsWith("$.") ||
                    code.startsWith(".") || code.startsWith("//") ||
                    code.startsWith("[") ->
                    getString(code, ctx, vars, jsLib, bindings)
                // {{key}}/{{searchKey}}：URL 模板语义——直接替换为百分号编码值
                code == "key" || code == "searchKey" ->
                    (vars[code] as? String)?.let { urlEncode(it) }
                else -> evalJs(code, ctx, vars, jsLib, bindings)
            }
            sb.append(v ?: "")
            i = e + 2
        }
        // 二次模板替换（v0.4.15）：@js: 整段脚本（OmoFun searchUrl IIFE）返回值里
        // 会保留 {{page}} 等占位符供模板系统继续替换——首轮结果仍含 {{ 时再跑
        // 一轮（只做一轮；变量不命中的占位符原样保留）
        val out = sb.toString()
        if (!out.contains("{{") || out == template) return out
        return try {
            val second = evalTemplate(out, ctx, vars, jsLib, bindings)
            // 仍含 {{ 且与首轮相同 → 说明是无法解析的占位符，保留原样
            if (second == out) out else second
        } catch (e: Exception) {
            out
        }
    }

    /** {{key}} 模板替换用：URL 百分号编码（空格→%20） */
    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---------------- JSONPath ----------------

    private class Raw(val value: Any?) {
        fun stringified(): String? = when (value) {
            null -> null
            is String -> value
            is List<*> -> value.joinToString(" ") { it?.toString() ?: "" }
            is Map<*, *> -> value.values.joinToString(" ") { it?.toString() ?: "" }
            else -> value.toString()
        }
    }

    private fun readJson(ctx: Ctx, path: String): Raw? {
        val parsed: DocumentContext = try {
            when (val v = ctx.value) {
                is String -> JsonPath.using(jsonConf).parse(v)
                else -> JsonPath.using(jsonConf).parse(v as Any)
            }
        } catch (e: Exception) {
            return null
        }
        return try {
            Raw(parsed.read(path))
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- legado 默认选择器语法 ----------------

    private val HTML_TAGS = setOf(
        "a", "p", "span", "div", "li", "ul", "ol", "table", "tr", "td", "th",
        "h1", "h2", "h3", "h4", "h5", "h6", "img", "em", "strong", "b", "i",
        "article", "section", "header", "footer", "small", "pre", "code",
        "button", "input", "label", "form", "nav", "main", "dl", "dt", "dd"
    )

    private fun isIndexToken(t: String): Boolean = t.matches(Regex("-?\\d+"))

    /** "class.bookbox.0"→(".bookbox",0)；"span.-1"→("span",-1)；"h2"→("h2",null) */
    private fun splitSelector(rule: String): Pair<String, Int?> {
        val r = rule.substringBefore("@").trim()
        val tokens = r.split('.').filter { it.isNotBlank() }
        val css = StringBuilder()
        var index: Int? = null
        var mode: String? = null
        var first = true
        for (t in tokens) {
            if (isIndexToken(t)) {
                index = t.toInt()
                continue
            }
            if (first) {
                first = false
                when {
                    t == "class" || t == "text" || t == "children" || t == "childs" -> mode = "class"
                    t == "id" -> mode = "id"
                    t == "tag" -> mode = "tag"
                    t in HTML_TAGS -> mode = "tag"   // 无前缀的常见标签名
                    else -> mode = "class"           // 其余按类名
                }
                when (mode) {
                    "class" -> css.append(".").append(t)
                    "tag" -> css.append(t)
                    else -> Unit
                }
            } else {
                when (mode) {
                    "id" -> css.append("#").append(t)
                    "tag" -> css.append(t)            // 后续 token 作标签/类名补充
                    else -> css.append(".").append(t)
                }
            }
        }
        return css.toString() to index
    }

    private fun attrOf(rule: String): String? {
        val at = rule.lastIndexOf('@')
        return if (at > 0 && !rule.startsWith("@") && at < rule.length - 1) {
            rule.substring(at + 1).trim()
        } else null
    }

    private fun selectByCss(root: Element, css: String, index: Int?, attr: String?): String? {
        if (css.isBlank()) return null
        val els = root.select(css)
        if (els.isEmpty()) return null
        val el = when {
            index == null -> els.first()
            index >= 0 -> els.getOrNull(index)
            else -> els.getOrNull(els.size + index)   // 负索引：倒数
        } ?: return null
        return when (attr) {
            null, "text", "textNodes", "ownText" -> el.text()
            "html", "allHtml" -> el.html()
            "all" -> el.outerHtml()
            else -> {
                val abs = el.absUrl(attr)
                if (abs.isNotBlank()) abs else el.attr(attr)
            }
        }
    }

    /** 末尾 ##regex[##replace] 净化 */
    private fun applyFilter(raw: String, filter: String): String {
        if (filter.isBlank()) return raw
        val parts = filter.split("##")
        if (parts.isEmpty() || parts[0].isBlank()) return raw
        val repl = parts.getOrNull(1) ?: ""
        return try {
            raw.replace(Regex(parts[0]), repl)
        } catch (e: Exception) {
            raw
        }
    }

    // ---------------- Rhino JS ----------------

    /**
     * 执行 JS：jsLib 前置拼接后直接执行，脚本完成值（最后表达式语句的值）即结果。
     * 环境注入：result（上下文文本）、vars（含 baseUrl/key/page）、
     * java / cache / source 绑定（全局可见，this 解构亦可取得）。
     */
    private fun evalJs(
        code: String,
        ctx: Ctx,
        vars: Map<String, Any?>,
        jsLib: String?,
        bindings: Map<String, Any?>
    ): String? {
        val raw = evalJsRaw(code, ctx, vars, jsLib, bindings)
        return raw?.let {
            when (it) {
                is String -> it
                else -> Context.toString(it)
            }
        }
    }

    /**
     * evalJs 的原始对象版（v0.4.15）：@js: 列表规则可能返回
     * NativeArray / java.util.ArrayList（OmoFun parseList→arr()），
     * toString 会变乱码——getItems 需拿原始 List 拆 Ctx。
     */
    private fun evalJsRaw(
        code: String,
        ctx: Ctx,
        vars: Map<String, Any?>,
        jsLib: String?,
        bindings: Map<String, Any?>
    ): Any? {
        val cx = try {
            Context.enter()
        } catch (e: Exception) {
            lastJsError = "Rhino Context.enter 失败：${e.message}"
            return null
        }
        lastJsError = null
        try {
            // Android 上禁 JIT 字节码生成，纯解释模式（兼容性最好）
            cx.optimizationLevel = -1
            // ES6 语法（const/箭头函数/解构赋值）：yck 等源的 jsLib 用 const{java,cache}=this 解构，
            // 默认 ES5 解析直接报 missing : after property id，必须提语言版本
            cx.setLanguageVersion(Context.VERSION_ES6)
            cx.setClassShutter(LanShutter)
            val scope: Scriptable = cx.initStandardObjects()
            // 供 jsLib 使用 Packages.org.jsoup 的简写
            cx.evaluateString(scope, "var org = Packages.org;", "<polyfill>", 1, null)
            val resultObj = when (val v = ctx.value) {
                is Element -> v.text()
                is Document -> v.text()
                null -> ""
                else -> v.toString()
            }
            scope.put("result", scope, resultObj ?: "")
            for ((k, v) in vars) {
                scope.put(k, scope, v ?: "")
            }
            // java / cache / source：全局绑定（顶层 this 即 global，解构 this 也能取得）
            for ((k, v) in bindings) {
                scope.put(k, scope, v ?: "")
            }
            val full = buildString {
                if (!jsLib.isNullOrBlank()) {
                    append(jsLib)
                    append("\n;\n")
                }
                append(code)
            }
            val ret = cx.evaluateString(scope, full, "<lanread>", 1, null)
            // NativeArray 等 Scriptable 包装为 Java 可用的 List（unwrap）
            return when (ret) {
                null -> {
                    // 脚本完成值为 JS null（无异常抛出）——单独记录，与真异常区分（诊断用）
                    lastJsError = "完成值为 null（无异常抛出）"
                    null
                }
                else -> Context.jsToJava(ret, Any::class.java)
            }
        } catch (e: Exception) {
            lastJsError = "${e.javaClass.simpleName}: ${e.message?.take(120)}"
            return null
        } catch (t: Throwable) {
            // StackOverflowError 等 java.lang.Error：Exception 捕不住，此前会静默返回 null
            lastJsError = "${t.javaClass.simpleName}: ${t.message?.take(120)}"
            return null
        } finally {
            try { Context.exit() } catch (ignore: Exception) { }
        }
    }
}
