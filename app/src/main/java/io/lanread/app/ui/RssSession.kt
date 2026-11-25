package io.lanread.app.ui

import io.lanread.app.source.RssRepository

/** 进程内会话：订阅文章导航传参（避免大 HTML 走 URL 编码） */
object RssSession {
    var articles: List<RssRepository.Article> = emptyList()
    var title: String = ""
}
