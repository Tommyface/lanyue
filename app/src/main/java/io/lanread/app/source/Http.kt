package io.lanread.app.source

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 网络层：统一 UA、编码嗅探（UTF-8/GBK 家族自动兼容） */
object Http {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} @ $url")
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val ctype = resp.header("Content-Type") ?: ""
            val declared = Regex("charset=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
                .find(ctype)?.groupValues?.getOrNull(1)
            return decode(bytes, declared)
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
