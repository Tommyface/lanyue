package io.lanread.app.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 网络层：统一 UA、编码嗅探（UTF-8/GBK 家族自动兼容）、相对 URL 拼接、
 * 源自定义请求头（Legado header 字段）、连接级自动重试、强制 HTTP/1.1。
 *
 * 强制 HTTP/1.1（v0.4.9）：部分 CDN/防护会对 HTTP/2 或非浏览器 TLS 指纹直接
 * RST 连接（真机 Connection reset），HTTP/1.1 + 重试可绕过其中大部分。
 */
object Http {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .build()

    const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun get(url: String): String = get(url, null, null)

    fun get(url: String, baseUrl: String?): String = get(url, baseUrl, null)

    /**
     * Legado 请求选项（v0.4.17）：searchUrl 形如 `URL,{"method":"POST","body":"k=v&...","headers":{...},"charset":"gbk"}`。
     * 解析尾部逗号后的 JSON 选项；无选项时为普通 GET。
     */
    data class ReqOpts(
        val method: String = "GET",
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val charset: String? = null
    )

    fun parseReqOpts(urlWithOpts: String): Pair<String, ReqOpts> {
        val t = urlWithOpts.trim()
        val comma = findOptsComma(t)
        if (comma < 0) return t to ReqOpts()
        val urlPart = t.substring(0, comma).trim()
        val optsRaw = t.substring(comma + 1).trim()
        if (!optsRaw.startsWith("{")) return t to ReqOpts()
        return try {
            val el = Json.parseToJsonElement(optsRaw)
            val o = el as? JsonObject ?: return t to ReqOpts()
            val method = (o["method"] as? JsonPrimitive)?.content?.uppercase() ?: "GET"
            val body = (o["body"] as? JsonPrimitive)?.content
            val headers = LinkedHashMap<String, String>()
            (o["headers"] as? JsonObject)?.forEach { (k, v) ->
                (v as? JsonPrimitive)?.let { p -> headers[k] = p.content }
            }
            val charset = (o["charset"] as? JsonPrimitive)?.content
            urlPart to ReqOpts(method, body, headers, charset)
        } catch (e: Exception) {
            t to ReqOpts()
        }
    }

    /** 顶层逗号且其后是 { 才当作选项分隔（避免 URL 查询串逗号误切） */
    private fun findOptsComma(t: String): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in t.indices) {
            val c = t[i]
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && (c == '{' || c == '[') -> depth++
                !inStr && (c == '}' || c == ']') -> depth--
                !inStr && c == ',' && depth == 0 -> {
                    // 逗号后（跳过空白）必须是 { 才算选项起点
                    var j = i + 1
                    while (j < t.length && t[j].isWhitespace()) j++
                    if (j < t.length && t[j] == '{') return i
                }
            }
        }
        return -1
    }

    /** 带 baseUrl 与源自定义头的 GET；连接级失败（reset/超时）自动重试一次 */
    fun get(url: String, baseUrl: String?, headers: Map<String, String>?): String {
        val (target, opts) = parseReqOpts(url)
        return fetch(target, baseUrl, headers, opts)
    }

    /** POST/GET 通用执行（v0.4.17）：支持源 URL 携带 Legado JSON 选项 */
    private fun fetch(url: String, baseUrl: String?, extraHeaders: Map<String, String>?, opts: ReqOpts): String {
        val target = absUrl(baseUrl, url)
        val builder = Request.Builder().url(target).apply {
            header("User-Agent", UA)
            extraHeaders?.forEach { (k, v) -> header(k, v) }
            opts.headers.forEach { (k, v) -> header(k, v) }
        }
        if (opts.method == "POST" && opts.body != null) {
            val ct = opts.headers["Content-Type"] ?: "application/x-www-form-urlencoded"
            builder.post(opts.body.toRequestBody(ct.toMediaType()))
        } else if (opts.method == "POST") {
            builder.post(ByteArray(0).toRequestBody(null))
        }
        val req = builder.build()
        var last: IOException? = null
        for (attempt in 1..2) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} @ $url")
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    val ctype = resp.header("Content-Type") ?: ""
                    val declared = opts.charset
                        ?: Regex("charset=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
                            .find(ctype)?.groupValues?.getOrNull(1)
                    return decode(bytes, declared)
                }
            } catch (e: IOException) {
                last = e
                if (attempt < 2) Thread.sleep(400)
            }
        }
        throw last ?: IOException("GET failed @ $url")
    }

    /** 解析 Legado 源 header 字段（JSON 字符串 → 请求头 map） */
    fun parseHeaders(raw: String?): Map<String, String>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val el = Json.parseToJsonElement(raw.trim())
            if (el is JsonObject) {
                val m = LinkedHashMap<String, String>()
                for ((k, v) in el) (v as? JsonPrimitive)?.let { p -> m[k] = p.content }
                if (m.isNotEmpty()) m else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 相对 URL 补全（v0.4.8）：
     *  - 绝对 http(s) → 原样；"//x" → "https://x"
     *  - "/path" → baseUrl 的 origin + path；"path" → baseUrl 去尾斜杠 + "/path"
     *  - 无 baseUrl 可用时尽力返回（调用方网络层会抛 scheme 错并被 UI 透出）
     */
    fun absUrl(baseUrl: String?, url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return u
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        val b = baseUrl?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return u
        return if (u.startsWith("/")) {
            val schemeEnd = b.indexOf("//") + 2
            val pathStart = b.indexOf('/', schemeEnd)
            (if (pathStart > 0) b.substring(0, pathStart) else b) + u
        } else {
            b.trimEnd('/') + "/" + u
        }
    }

    private fun decode(bytes: ByteArray, declared: String?): String {
        val cs = if (!declared.isNullOrEmpty()) normalize(declared) else sniff(bytes)
        return try {
            String(bytes, charset(cs))
        } catch (e: Exception) {
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun sniff(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "UTF-8"
        val head = String(bytes, 0, minOf(2048, bytes.size), Charsets.ISO_8859_1)
        val m = Regex("charset=[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.getOrNull(1)
        return if (!m.isNullOrEmpty()) normalize(m) else "UTF-8"
    }

    private fun normalize(c: String): String = when (c.lowercase()) {
        "gb2312", "gbk" -> "GB18030"
        "utf8" -> "UTF-8"
        else -> c
    }
}
