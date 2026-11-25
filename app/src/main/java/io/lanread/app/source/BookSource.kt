package io.lanread.app.source

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

/**
 * Legado 书源模型（兼容子集）。
 * 只声明本应用实际消费的字段，其余字段由 ignoreUnknownKeys 忽略。
 */
@Serializable
data class BookSource(
    val bookSourceUrl: String = "",
    val bookSourceName: String = "",
    val bookSourceGroup: String? = null,
    val bookSourceType: Int? = null,
    val enabled: Boolean? = null,
    val enabledExplore: Boolean? = null,
    val jsLib: String? = null,
    val loginUi: String? = null,      // 登录页控件 JSON（数组字符串）
    val loginUrl: String? = null,     // 登录页 JS 函数定义（action 的前置库）
    val exploreUrl: String? = null,   // 发现页入口：@js: 动态生成 或 "名称::URL" 行
    val ruleSearch: SearchRule? = null,
    val ruleBookInfo: BookInfoRule? = null,
    val ruleToc: TocRule? = null,
    val ruleContent: ContentRule? = null,
    val ruleExplore: ExploreRule? = null
)

@Serializable
data class ExploreRule(
    val bookList: String? = null,
    val name: String? = null,
    val author: String? = null,
    val bookUrl: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val kind: String? = null,
    val lastChapter: String? = null,
    val wordCount: String? = null
)

@Serializable
data class SearchRule(
    val searchUrl: String? = null,
    val bookList: String? = null,
    val name: String? = null,
    val author: String? = null,
    val bookUrl: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val kind: String? = null,
    val lastChapter: String? = null
)

@Serializable
data class BookInfoRule(
    val init: String? = null,
    val name: String? = null,
    val author: String? = null,
    val kind: String? = null,
    val intro: String? = null,
    val coverUrl: String? = null,
    val lastChapter: String? = null,
    val tocUrl: String? = null
)

@Serializable
data class TocRule(
    val chapterList: String? = null,
    val chapterName: String? = null,
    val chapterUrl: String? = null
)

@Serializable
data class ContentRule(
    val content: String? = null
)

object BookSourceParser {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** 解析一段文本（JSON 数组或单对象），单条失败不影响其余 */
    fun parseText(text: String): List<BookSource> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            if (trimmed.startsWith("[")) {
                val arr: JsonArray = json.parseToJsonElement(trimmed).jsonArray
                arr.mapNotNull { el ->
                    runCatching { json.decodeFromJsonElement(BookSource.serializer(), el) }.getOrNull()
                }
            } else {
                listOf(json.decodeFromString(BookSource.serializer(), trimmed))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun parseSingle(storedJson: String): BookSource? = try {
        json.decodeFromString(BookSource.serializer(), storedJson)
    } catch (e: Exception) {
        null
    }
}

/** 登录页控件（Legado loginUi）：按钮带 action（JS）、输入框存变量 */
@Serializable
data class LoginUiItem(
    val name: String = "",
    val type: String? = null,      // button / text / password ...
    val action: String? = null,    // 按钮点击执行的 JS
    val value: String? = null      // 输入框默认值
)

/** 发现页入口（exploreUrl）：静态 "名称::URL" 行或 @js: 动态生成的 JSON 数组 */
@Serializable
data class ExploreItem(
    val title: String = "",
    val url: String = ""
)

object LoginUiParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(raw: String?): List<LoginUiItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(LoginUiItem.serializer()),
                raw.trim()
            )
        } catch (e: Exception) {
            emptyList()
        }
    }
}

object ExploreUrlParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 解析发现页入口。两类格式：
     *  1) "@js:\n...JSON.stringify(result);" —— 执行 JS 取返回数组
     *     （动态标签，返回字段 title/url）
     *  2) "名称::URL\n名称::URL" —— 静态行解析
     * 动态执行失败时回退空列表（由调用方提示）。
     */
    fun parseStatic(raw: String?): List<ExploreItem> {
        if (raw.isNullOrBlank()) return emptyList()
        if (raw.trim().startsWith("@js:")) return emptyList()   // 动态格式不在此处理
        return raw.split('\n')
            .mapNotNull { line ->
                val i = line.indexOf("::")
                if (i <= 0) return@mapNotNull null
                val t = line.substring(0, i).trim()
                val u = line.substring(i + 2).trim()
                if (t.isEmpty() || u.isEmpty()) return@mapNotNull null
                ExploreItem(t, u)
            }
    }

    fun parseDynamicJson(jsReturn: String?): List<ExploreItem> {
        if (jsReturn.isNullOrBlank()) return emptyList()
        return try {
            // JS 返回 [{title, url, style{...}}] 结构
            val arr = json.parseToJsonElement(jsReturn.trim()).let { el ->
                when (el) {
                    is kotlinx.serialization.json.JsonArray -> el
                    else -> return emptyList()
                }
            }
            arr.mapNotNull { e ->
                runCatching {
                    val obj = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val t = obj["title"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return@mapNotNull null
                    val u = obj["url"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return@mapNotNull null
                    ExploreItem(t, u)
                }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
