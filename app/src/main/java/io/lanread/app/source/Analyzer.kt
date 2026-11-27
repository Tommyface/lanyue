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

    /** java.getString/getElements 的当前规则上下文（线程本地：JS 在调用线程同步执行） */
    internal class RuleEvalContext(
        val ctx: Ctx,
        val vars: Map<String, Any?>,
        val jsLib: String?,
        val bindings: Map<String, Any?>
    )

    internal val currentRuleEval = ThreadLocal<RuleEvalContext?>()

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

    /** jsLib 里的 java.* 扩展（Legado JsExtensions 兼容子集，v0.4.22 大幅补齐） */
    class JsExt(
        private val store: MutableMap<String, String?> = ConcurrentHashMap(),
        private val toastFn: (String) -> Unit = {}
    ) {
        fun toast(s: String?) {
            if (s != null) toastFn(s)
        }

        fun longToast(s: String?) {
            if (s != null) toastFn(s)
        }

        fun log(s: String?) {
            if (s != null) println("[js] $s")
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

        /** WebView 的 UA（源按浏览器口径拼请求时用） */
        fun getWebViewUA(): String = Http.UA

        /**
         * Legado java.ajax：url 支持 `URL,{options}`（POST/body/headers/charset——Http 层解析）。
         * 燃文（取首页令牌再 POST）/ 群小说（_token）/ 茂林 / 书山聚合等源的 searchUrl 全靠它。
         * 失败返回 null（调用方脚本自行兜底）。
         */
        fun ajax(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return try {
                Http.get(url, null, null)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * java.post(url, body, headers)：OmoFun OM_pass 种 cookie、燃文 API POST 等用。
         * headers 参数接受 JS 对象（Rhino NativeJavaObject 映射）或 JSON 字符串。
         * 返回响应体（失败 null）。
         */
        fun post(url: String?, body: String?, headers: Any?): String? {
            if (url.isNullOrBlank()) return null
            val hs = headersToMap(headers)
            val opts = Http.ReqOpts(method = "POST", body = body ?: "", headers = hs)
            return try {
                Http.get(url, null, null, opts)
            } catch (e: Exception) {
                null
            }
        }

        /** java.connect(urlStr)：Legado 简化连接（GET），返回可 body() 的对象；这里直接返回体字符串包装 */
        fun connect(url: String?): ResponseWrap? {
            if (url.isNullOrBlank()) return null
            return try {
                ResponseWrap(Http.get(url, null, null))
            } catch (e: Exception) {
                null
            }
        }

        /**
         * v0.4.24：java.get(urlStr, options)——无忧书城族写法 `java.get(su,{}).headers('Location')`：
         * 返回 Response 包装（headers(name)/body()）。options 兼容 JS 对象/JSON 字符串/空。
         * 与存量 get(k)（变量存取）同名重载：Rhino 按参数个数分发，单参为存取、双参为 HTTP。
         */
        fun get(url: String?, options: Any?): ResponseWrap? {
            if (url.isNullOrBlank()) return null
            val hs = headersToMap(options)
            val opts = Http.ReqOpts(method = "GET", headers = hs)
            return try {
                ResponseWrap(Http.get(url, null, null, opts))
            } catch (e: Exception) {
                null
            }
        }

        /** OmoFun 的 r.body() 调用形态（connect/get 返回值链式取体）；headers(name) 供重定向读取 */
        class ResponseWrap(private val bodyStr: String) {
            fun body(): String = bodyStr
            fun headers(name: String?): String? =
                if (name == null) null else
                    (Http.lastHeaders[name] ?: Http.lastHeaders[name.lowercase()])?.firstOrNull()
            override fun toString(): String = bodyStr
        }

        /** 设备标识（1336 合集里源拼签名用）——固定值，不暴露真机信息 */
        fun androidId(): String = "lanread0000device"
        fun deviceID(imei: String?): String = "86${(imei ?: "").hashCode().toLong().let { if (it < 0) -it else it }}"

        /** v0.4.24：DESede（3DES）加密——245 集综合源的 token 签名 */
        fun tripleDESEncodeBase64(data: String?, key: String?): String? {
            if (data == null || key == null) return null
            return runCatching {
                val des = javax.crypto.spec.DESedeKeySpec(key.toByteArray(Charsets.UTF_8))
                val factory = javax.crypto.SecretKeyFactory.getInstance("DESede")
                val cipher = javax.crypto.Cipher.getInstance("DESede/ECB/PKCS5Padding")
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, factory.generateSecret(des))
                android.util.Base64.encodeToString(cipher.doFinal(data.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP)
            }.getOrNull()
        }

        /** JS 传入的 headers（NativeJavaObject/Map/JSON 字符串）→ Map */
        private fun headersToMap(headers: Any?): Map<String, String> {
            return when (headers) {
                null -> emptyMap()
                is Map<*, *> -> headers.entries.associate { it.key.toString() to (it.value?.toString() ?: "") }
                else -> {
                    // JS 对象：通过 Rhino Scriptable 取键（Json 序列化最稳）
                    val s = headers.toString()
                    if (s.trimStart().startsWith("{")) {
                        Http.parseHeaders(s) ?: emptyMap()
                    } else emptyMap()
                }
            }
        }

        /** 批量 ajax（部分源聚合线路探测用）；输入 JS 数组（Rhino NativeArray 即 List） */
        fun ajaxAll(urls: Any?): Any? {
            val list = urls as? List<*> ?: return null
            val out = ArrayList<String?>(list.size)
            for (u in list) out.add(ajax(u?.toString()))
            return out
        }

        /**
         * java.getString(rule)：对当前规则上下文求值（933 订阅源 ruleContent 第一句
         * `java.getString('h1@text')`）。上下文由 evalJsRaw 在执行前写入 ThreadLocal。
         * String 上下文（原网页 HTML）先 Jsoup 解析再选——getString 的 css 分支只认 Element。
         */
        fun getString(rule: String?): String? {
            val rc = currentRuleEval.get() ?: return null
            if (rule.isNullOrBlank()) return null
            return this@Analyzer.run { getString(rule, htmlCtx(rc.ctx), rc.vars, rc.jsLib, rc.bindings) }
        }

        /** java.getElements(rule)：按列表选择器取元素集合（返回 jsoup Elements，List 兼容） */
        fun getElements(rule: String?): Any? {
            val rc = currentRuleEval.get() ?: return null
            if (rule.isNullOrBlank()) return null
            val root = htmlCtx(rc.ctx).asElement() ?: return null
            val (css, _) = splitSelector(rule.trim().removePrefix("@css:"))
            return root.select(css)
        }

        private fun htmlCtx(ctx: Ctx): Ctx = when (val v = ctx.value) {
            is Element, is Document -> ctx
            is String -> runCatching { Ctx(Jsoup.parse(v, "")) }.getOrDefault(ctx)
            else -> ctx
        }

        // ---------------- 编码 / 哈希 ----------------

        fun base64Encode(s: String?): String? =
            s?.let { android.util.Base64.encodeToString(it.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP) }

        fun base64Decode(s: String?): String? =
            s?.let { String(base64DecodeToByteArray(it) ?: return null, Charsets.UTF_8) }

        fun base64DecodeToByteArray(s: String?): ByteArray? =
            s?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() }

        fun base64EncodeToByteArray(b: ByteArray?): String? =
            b?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }

        fun strToBytes(s: String?): ByteArray? = s?.toByteArray(Charsets.UTF_8)

        fun hexDecodeToByteArray(hex: String?): ByteArray? {
            if (hex == null) return null
            val h = hex.trim()
            if (h.length % 2 != 0) return null
            return runCatching {
                val out = ByteArray(h.length / 2)
                for (i in out.indices) out[i] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                out
            }.getOrNull()
        }

        fun hexDecodeToString(hex: String?): String? =
            hexDecodeToByteArray(hex)?.toString(Charsets.UTF_8)

        fun md5Encode(s: String?): String? {
            if (s == null) return null
            return runCatching {
                val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
                d.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            }.getOrNull()
        }

        // ---------------- URI 编码（OmoFun 等源拼搜索路径用） ----------------

        /** JS encodeURI 语义：保留 /?&=@+$#等 URI 保留字符 */
        fun encodeURI(s: String?): String? = encodeWith(s, KEEP_URI)

        /** JS encodeURIComponent 语义 */
        fun encodeURIComponent(s: String?): String? = encodeWith(s, KEEP_NONE)

        fun decodeURI(s: String?): String? = decodeWith(s)

        fun decodeURIComponent(s: String?): String? = decodeWith(s)

        private val KEEP_URI = setOf(';', '/', '?', ':', '@', '&', '=', '+', '$', ',', '#', '-', '_', '.', '!', '~', '*', '\'', '(', ')')
        private val KEEP_NONE = setOf('-', '_', '.', '!', '~', '*', '\'', '(', ')')

        private fun encodeWith(s: String?, keep: Set<Char>): String? {
            if (s == null) return null
            val enc = java.net.URLEncoder.encode(s, "UTF-8")
            val sb = StringBuilder(enc.length)
            for (c in enc) {
                when {
                    c == '+' -> sb.append("%20")
                    c == '%' -> sb.append(c)
                    keep.contains(c) -> sb.append(c)
                    else -> sb.append(c)
                }
            }
            // URLEncoder 会转义的保留字符还原回 keep 集合成员
            var out = sb.toString()
            for (k in keep) {
                val hex = "%" + "%02X".format(k.code)
                out = out.replace(hex, k.toString())
            }
            return out
        }

        private fun decodeWith(s: String?): String? {
            if (s == null) return null
            return runCatching { java.net.URLDecoder.decode(s.replace("+", "%20"), "UTF-8") }.getOrNull()
        }

        /**
         * Legado 同名 API（v0.4.19 起，v0.4.22 扩参数）：key/iv 可为 String（UTF-8 字节）
         * 或 byte[]（OmoFun 先 java.strToBytes/java.hexDecodeToByteArray 再传入）。
         */
        fun createSymmetricCrypto(transformation: String?, key: Any?, iv: Any?): SymmetricCrypto? {
            if (transformation.isNullOrBlank() || key == null) return null
            val keyBytes = bytesOf(key) ?: return null
            val ivBytes = iv?.let { bytesOf(it) } ?: keyBytes
            return try {
                SymmetricCrypto(transformation.lowercase(), keyBytes, ivBytes)
            } catch (e: Exception) {
                null
            }
        }

        private fun bytesOf(v: Any?): ByteArray? = when (v) {
            is String -> v.toByteArray(Charsets.UTF_8)
            is ByteArray -> v
            else -> null
        }

        class SymmetricCrypto(
            private val transformation: String,
            private val key: ByteArray,
            private val iv: ByteArray
        ) {
            /** 解密（图片字节等）：fail 返回 null（调用方 fallback 原始数据） */
            fun decrypt(data: Any?): ByteArray? = decryptBytes(
                when (data) {
                    null -> null
                    is ByteArray -> data
                    else -> runCatching { android.util.Base64.decode(data.toString(), android.util.Base64.DEFAULT) }
                        .getOrElse { data.toString().toByteArray(Charsets.UTF_8) }
                }
            )

            /** 解密为字符串（OmoFun 视频播放地址：c.decryptStr(cipher)） */
            fun decryptStr(data: Any?): String? = decrypt(data)?.toString(Charsets.UTF_8)

            /** v0.4.24：加密（点众阅读族 c.encrypt(JSON.stringify(data)) → 返回 ByteArray） */
            fun encrypt(data: Any?): ByteArray? = encryptBytes(
                when (data) {
                    null -> null
                    is ByteArray -> data
                    else -> data.toString().toByteArray(Charsets.UTF_8)
                }
            )

            fun encryptBytes(data: ByteArray?): ByteArray? {
                if (data == null || data.isEmpty()) return null
                return try {
                    val t = transformation.replace("pkcs7padding", "pkcs5padding")
                    val cipher = javax.crypto.Cipher.getInstance(t)
                    val sks = javax.crypto.spec.SecretKeySpec(key, t.substringBefore('/').uppercase())
                    when (t.substringAfter('/').substringBefore('/')) {
                        "cbc", "cfb", "ofb", "ctr" -> {
                            val ivx = ByteArray(16) { if (it < iv.size && it < 16) iv[it] else 0 }
                            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, sks, javax.crypto.spec.IvParameterSpec(ivx))
                        }
                        else -> cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, sks)
                    }
                    val out = cipher.doFinal(data)
                    if (out != null && out.isNotEmpty()) out else null
                } catch (e: Exception) {
                    null
                }
            }

            fun decryptBytes(data: ByteArray?): ByteArray? {
                if (data == null || data.isEmpty()) return null
                return try {
                    // javax.crypto 不认 pkcs7padding 名称（AES 下与 PKCS5 等价）；Android 亦兼容
                    val t = transformation.replace("pkcs7padding", "pkcs5padding")
                    val cipher = javax.crypto.Cipher.getInstance(t)
                    val sks = javax.crypto.spec.SecretKeySpec(key, t.substringBefore('/').uppercase())
                    when (t.substringAfter('/').substringBefore('/')) {
                        "cbc", "cfb", "ofb", "ctr" -> {
                            // IV 必须与块长一致（AES=16）：密钥短时补零，长时截断
                            val ivx = ByteArray(16) { if (it < iv.size && it < 16) iv[it] else 0 }
                            cipher.init(
                                javax.crypto.Cipher.DECRYPT_MODE, sks,
                                javax.crypto.spec.IvParameterSpec(ivx)
                            )
                        }
                        else -> cipher.init(javax.crypto.Cipher.DECRYPT_MODE, sks)
                    }
                    val out = cipher.doFinal(data)
                    // PKCS5 去填充（块对齐失败时返回 null 走 fallback）
                    if (out != null && out.isNotEmpty()) out else null
                } catch (e: Exception) {
                    null
                }
            }
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

        /** v0.4.24：source.getKey()/source.key——书源 URL 井号后的备用地址（Legado 语义）。
         *  无忧/网书/夜寒等源 searchUrl 以 source.key 为真实搜索域（书源 URL 是展示壳）。 */
        fun getKey(): String = store["key"] ?: keyFallback
        fun setKey(v: String) { store["key"] = v }

        /** key 兜底：从 baseUrl 变量提取 # 后段（searchOne 阶段已注入 key） */
        private val keyFallback: String
            get() = currentRuleEval.get()?.vars?.get("bookSourceKey") as? String ?: ""
    }

    /** v0.4.24：cookie 全局对象（夜寒书库族 `cookie.removeCookie(url)` / `cookie.getCookie(url)`） */
    class JsCookie {
        fun getCookie(url: String?): String? = Http.cookiesOf(url)
        fun removeCookie(url: String?) = Http.clearCookies(url)
        fun setCookie(url: String?, value: String?) = Http.setCookie(url, value)
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
                    // 两段式：<js>脚本</js> 后半段（如 $.list / dl.kb-b）
                    // v0.4.23：rest 兼容两种形态——JSONPath（$.list）与 Legado 默认语法
                    //（dl.kb-b）——偶然中文 or7Search 返回 HTML 后接 dl.kb-b 选择即此形态，
                    // 旧版一律当 JSONPath 传 readJson → HTML 串必然 null → 列表空。
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
                    } else if (rest.startsWith("$")) {
                        val v = readJson(Ctx(jsResult), rest)?.value
                        if (v is List<*>) v.map { Ctx(it) } else emptyList()
                    } else {
                        // Legado 默认语法（dl.kb-b / a.xxx）：jsResult 作新 body 递归求值
                        getItems(jsResult, baseUri, rest, vars, jsLib, bindings)
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
                    // v0.4.23：①含空格 / # 开头的规则是原生 CSS（群小说 #hotcontent .item），
                    // 整串交 jsoup——旧版 splitSelector 会把 # 开头 token 当 class 拼成 .#xxx 废选择器；
                    // ②body 是 JSON（七猫 API）时无前缀规则（data.books）按 JSONPath 处理。
                    val trimmedBody = body.trimStart()
                    val bodyIsJson = trimmedBody.startsWith("{") || trimmedBody.startsWith("[")
                    val looksNativeCss = r.contains(' ') || r.startsWith("#") || r.contains(">") || r.contains("~")
                    when {
                        bodyIsJson && !looksNativeCss -> {
                            val path = if (r.startsWith("$")) r else "$." + r
                            val v = readJson(Ctx(body), path)?.value
                            if (v is List<*>) v.map { Ctx(it) } else emptyList()
                        }
                        looksNativeCss -> selectItems(body, baseUri, r.substringBefore("@").trim())
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
                // v0.4.23：整段 JS 内嵌的 {{$.id}} 等 {{规则}} 占位先替换再执行
                //（七猫 bookUrl=`@js:qmDetail('{{$.id}}')`——JS 字符串里的模板）。
                // 仅处理 {{}} 出现在 JS 字符串内的场景：逐段求值拼回。
                val prepared = if (r.contains("{{") && r.contains("}}")) {
                    fillPlaceholders(r, ctx, vars, jsLib, bindings)
                } else r
                evalMain(prepared, ctx, vars, jsLib, bindings)
            } catch (e: Exception) {
                null
            }
        }
        if (r.contains("{{") && r.contains("}}")) {
            val tpl = evalTemplate(r, ctx, vars, jsLib, bindings)
            if (tpl.isNotBlank()) return tpl
        }
        // v0.4.23：@js: 中缀两段式（爱奇艺漫画 bookUrl=`$.bookId@js:kbDetailUrl(result)`）：
        // 前段提取值作为 result 传入后段 JS——Legado 的字段级 JS 管道。
        // 后段返回值里保留的 {{kbSignTail(...)}} 占位继续模板替换（kbDetailUrl 拼签名 URL 场景）。
        if (!r.startsWith("@js:")) {
            val ji = r.indexOf("@js:")
            if (ji > 0) {
                val front = r.substring(0, ji).trim()
                val jsCode = r.substring(ji + 4)
                if (front.isNotBlank() && jsCode.isNotBlank()) {
                    val frontVal = getString(front, ctx, vars, jsLib, bindings) ?: return null
                    return try {
                        val jsRet = evalJs(jsCode, Ctx(frontVal), vars, jsLib, bindings) ?: return null
                        if (jsRet.contains("{{")) secondPass(jsRet, ctx, vars, jsLib, bindings) else jsRet
                    } catch (e: Exception) {
                        null
                    }
                }
            }
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
                // v0.4.22：@css: 后是完整 CSS 选择器（nth-of-type/>/伪类等高级语法），
                // 不能走 splitSelector 的 Legado token 切分（点号会把伪类/组合器拆烂）。
                // 尾部 @attr 剥离（attrOf 语义），其余整串直接交 jsoup。
                val body = r.removePrefix("@css:")
                val root = ctx.asElement() ?: return null
                selectByCss(root, body.substringBefore("@").trim(), null, attrOf(body))
            }
            else -> {
                // 前导 @ 容错（v0.4.14）：Legado 规则 `@a@href` / `@span.-2@text`（yck 订阅源
                // ruleDescription/rulePubDate 的实用写法）此前 splitSelector 截到空前缀 → css 为空
                // → 提取 null → 一键导入 URL 残缺（/yuedu//json/id/.json）与时间行空白。
                // @js:/@css:/@json: 已在前面分支处理，此处剩余前导 @ 一律剥离。
                val rr = if (r.startsWith("@")) r.substring(1) else r
                val v = ctx.value
                // v0.4.23：纯属性名规则（`@title` / `@href`）与裸文本规则（`text`/`html`）——
                // 列表项已是目标元素时 Legado 语义是取自身属性/文本（搬山人 @title、茂林 text）。
                val selfEl = ctx.asElement()
                val selfText = rr in SELF_TEXT_RULES
                val selfAttr = rr in ATTR_NAMES
                when {
                    selfText && selfEl != null -> when (rr) {
                        "html", "allHtml" -> selfEl.html()
                        "all" -> selfEl.outerHtml()
                        "ownText" -> selfEl.ownText()
                        else -> selfEl.text()
                    }
                    selfAttr && selfEl != null -> {
                        val abs = selfEl.absUrl(rr)
                        if (abs.isNotBlank()) abs else selfEl.attr(rr)
                    }
                    else -> {
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
            // v0.4.22：JS 失败不再回退原始模板（旧版会把 <js>... 当 URL 去 404，报错被掩盖）——
            // 返回空串，调用方用 lastJsError 拼可读原因
            val ret = evalJs(code.trim(), ctx, vars, jsLib, bindings) ?: return ""
            return secondPass(ret, ctx, vars, jsLib, bindings)
        }
        if (t.startsWith("@js:")) {
            val ret = evalJs(t.removePrefix("@js:"), ctx, vars, jsLib, bindings) ?: return ""
            return secondPass(ret, ctx, vars, jsLib, bindings)
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

    /** v0.4.22：整段 JS 返回值里的 {{page}} 等占位符继续由模板系统替换
     *（8100 型 OmoFun searchUrl：JS 返回 '...----------{{page}}---.html'）。
     * 与旧版 v0.4.15 的二次替换语义对齐，但作用于整段 JS 分支。 */
    private fun secondPass(
        ret: String,
        ctx: Ctx,
        vars: Map<String, Any?>,
        jsLib: String?,
        bindings: Map<String, Any?>
    ): String {
        if (!ret.contains("{{")) return ret
        return try {
            val second = evalTemplate(ret, ctx, vars, jsLib, bindings)
            if (second == ret) ret else second
        } catch (e: Exception) {
            ret
        }
    }

    /** {{key}} 模板替换用：URL 百分号编码（空格→%20） */
    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * v0.4.23：JS 代码内嵌 {{规则}} 占位替换（七猫 `@js:qmDetail('{{$.id}}')`）。
     * {{$.id}} → getString 对当前 item 上下文求值；{{js 表达式}} → evalJs；
     * 不命中变量/求值失败的占位原样保留（脚本侧自行兜底）。
     */
    private fun fillPlaceholders(
        code: String,
        ctx: Ctx,
        vars: Map<String, Any?>,
        jsLib: String?,
        bindings: Map<String, Any?>
    ): String {
        if (!code.contains("{{")) return code
        val sb = StringBuilder()
        var i = 0
        while (i < code.length) {
            val s = code.indexOf("{{", i)
            if (s < 0) {
                sb.append(code.substring(i)); break
            }
            sb.append(code.substring(i, s))
            val e = code.indexOf("}}", s + 2)
            if (e < 0) {
                sb.append(code.substring(s)); break
            }
            val ph = code.substring(s + 2, e)
            val v: String? = when {
                ph.startsWith("@") || ph.startsWith("$.") || ph.startsWith(".") || ph.startsWith("[") ->
                    getString(ph, ctx, vars, jsLib, bindings)
                else -> try {
                    evalJs(ph, ctx, vars, jsLib, bindings)
                } catch (ex: Exception) {
                    null
                }
            }
            sb.append(v ?: code.substring(s, e + 2))
            i = e + 2
        }
        return sb.toString()
    }

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

    /** v0.4.23：常见 HTML 属性名（纯属性规则的自身取值判定） */
    private val ATTR_NAMES = setOf(
        "title", "href", "src", "alt", "class", "id", "name", "value",
        "content", "property", "rel", "type", "style", "width", "height",
        "data-original", "data-src", "data-url", "data-title", "data-rid",
        "charset", "lang", "target", "placeholder"
    )

    /** v0.4.23：裸文本规则（text/ownText/html/all）——列表项自身内容（茂林 chapterName=text） */
    private val SELF_TEXT_RULES = setOf("text", "textNodes", "ownText", "html", "allHtml", "all")

    /** "class.bookbox.0"→(".bookbox",0)；"span.-1"→("span",-1)；"h2"→("h2",null)
     *
     * v0.4.23：①含空格/冒号/属性选择器的规则是原生 CSS（dt a@text / meta[property="…"]@content）
     * 原样返回交 jsoup——旧版把 "dt a" 当 class 拼成 .dt a 必然空；
     * ②tag 模式后续 token 非标签名时是 class（dl.kb-b → "dl.kb-b"）——旧版直接拼接成 dlkb-b。 */
    private fun splitSelector(rule: String): Pair<String, Int?> {
        val r = rule.substringBefore("@").trim()
        if (r.contains(' ') || r.contains(':') || r.contains('[')) {
            return r to null
        }
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
                    "tag" -> {
                        // 后续 token：标签名 → 子元素；否则 class（dl.kb-b → dl.kb-b）
                        if (t in HTML_TAGS) css.append(" ").append(t) else css.append(".").append(t)
                    }
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
        // v0.4.22：暴露当前规则上下文给 java.getString/getElements（933 ruleContent 等）；
        // 嵌套调用（JS 内调 getString 又触发新 JS）时保存/恢复前值，不破坏外层
        val prevRuleEval = currentRuleEval.get()
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
            // v0.4.23：result 注入原始上下文对象——列表项字段规则（8035 手机小说
            // `@js:(function(){var e=result; e.select('p.title')...})()`）需要 Element 的
            // select/attr API；字符串上下文（bookList 的整页 HTML）保持 String 不变，
            // 漫蛙/OmoFun 的 String(result||'') 再 Jsoup.parse 链路不受影响。
            val resultObj: Any? = when (val v = ctx.value) {
                is Element -> v
                is Document -> v
                null -> ""
                else -> v
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
            // v0.4.22：暴露当前规则上下文给 java.getString/getElements（933 ruleContent 等）
            currentRuleEval.set(RuleEvalContext(ctx, vars, jsLib, bindings))
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
            currentRuleEval.set(prevRuleEval)
            try { Context.exit() } catch (ignore: Exception) { }
        }
    }
}
