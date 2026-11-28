package io.lanread.app.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

/**
 * Legado 订阅源（RssSource）模型 —— 兼容子集。
 * 只声明本应用消费的字段，其余由 ignoreUnknownKeys 忽略。
 */
@Serializable
data class RssSource(
    val sourceName: String = "",
    val sourceUrl: String = "",
    val sourceGroup: String? = null,
    val sourceIcon: String? = null,
    val header: String? = null,        // 源自定义请求头（JSON 字符串）
    val sortUrl: String? = null,          // 分组入口：每行 "名称::URL"
    val articleStyle: Int? = null,        // 0=纯文本 1=自定义HTML
    val enableJs: Boolean? = null,
    val jsLib: String? = null,            // 全局 JS 库（函数定义）
    val style: String? = null,            // 正文页 CSS
    val ruleArticles: String? = null,     // 文章列表规则
    val ruleTitle: String? = null,
    val ruleLink: String? = null,
    val rulePubDate: String? = null,
    val ruleDescription: String? = null, // 正文模板（可含 {{规则}}/{{JS}}）
    val ruleContent: String? = null,     // v0.4.20：详情页内容规则（视频源自绘播放器 HTML）
    val ruleImage: String? = null,        // v0.4.20：列表封面规则
    val searchUrl: String? = null,        // v0.4.20：搜索 URL（视频订阅源搜索）
    val ruleNextPage: String? = null,
    val enabled: Boolean? = null,
    val singleUrl: Boolean? = null,
    val loadWithBaseUrl: Boolean? = null,
    val enabledCookieJar: Boolean? = null,
    val cacheFirst: Boolean? = null,
    val showWebLog: Boolean? = null,
    val preload: Boolean? = null,
    val customOrder: Int? = null,
    val lastUpdateTime: Long? = null,
    val type: Int? = null
) {
    /** 解析 sortUrl 为分组列表（丢弃无 :: 的行）；无 sortUrl 时兜底单分组=源首页（singleUrl 类） */
    fun groups(): List<RssGroup> {
        val raw = sortUrl
        if (raw.isNullOrBlank()) return listOf(RssGroup("首页", sourceUrl))
        return raw.split('\n')
            .mapNotNull { line ->
                val i = line.indexOf("::")
                if (i <= 0) return@mapNotNull null
                val title = line.substring(0, i).trim()
                val url = line.substring(i + 2).trim()
                if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
                RssGroup(title, url)
            }
    }
}

data class RssGroup(val title: String, val urlTemplate: String)

object RssSourceParser {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parseText(text: String): List<RssSource> {
        // v0.4.22：剥离 UTF-8 BOM（Windows 下载的源 JSON 常带）
        val trimmed = text.trim().removePrefix("﻿")
        if (trimmed.isEmpty()) return emptyList()
        return try {
            if (trimmed.startsWith("[")) {
                val arr: JsonArray = json.parseToJsonElement(trimmed).jsonArray
                arr.mapNotNull { el ->
                    runCatching { json.decodeFromJsonElement(RssSource.serializer(), el) }.getOrNull()
                }
            } else {
                listOf(json.decodeFromString(RssSource.serializer(), trimmed))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseSingle(storedJson: String): RssSource? = try {
        json.decodeFromString(RssSource.serializer(), storedJson)
    } catch (e: Exception) {
        null
    }

    /** 判断文本是不是订阅源（区分于书源）：有 sourceName+sourceUrl 且带订阅源特征字段 */
    fun looksLikeRss(text: String): Boolean {
        val list = parseText(text)
        if (list.isEmpty()) return false
        return list.any {
            it.sourceName.isNotBlank() && it.sourceUrl.isNotBlank() &&
                (it.ruleArticles != null || it.sortUrl != null)
        }
    }
}
