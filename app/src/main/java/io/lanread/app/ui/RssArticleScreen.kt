package io.lanread.app.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import io.lanread.app.data.AppDatabase
import io.lanread.app.source.RssRepository
import io.lanread.app.source.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RssArticleViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val rssRepo = RssRepository(db, app, SourceRepository(db, app))

    suspend fun importAuto(url: String): String =
        withContext(Dispatchers.IO) { rssRepo.importAuto(url) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssArticleScreen(nav: NavController, index: Int) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: RssArticleViewModel = viewModel(factory = viewModelFactory {
        initializer { RssArticleViewModel(app) }
    })
    val scope = rememberCoroutineScope()

    val articles = RssSession.articles
    val article = articles.getOrNull(index)
    var importMsg by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        article?.title ?: "文章",
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->

        /** 处理 legado:// 一键导入：拦截后调内部导入闭环 */
        fun handleLegado(url: String) {
            val src = runCatching {
                android.net.Uri.parse(url).getQueryParameter("src")
            }.getOrNull()
            if (src.isNullOrBlank()) {
                importMsg = "链接缺少 src 参数，无法导入"
                return
            }
            importing = true
            importMsg = null
            scope.launch {
                val msg = vm.importAuto(src)
                importMsg = msg
                importing = false
            }
        }

        if (article == null) {
            Text(
                "文章不存在（会话已失效，请返回列表重进）",
                Modifier.padding(padding).padding(24.dp),
                fontSize = 14.sp
            )
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: android.webkit.WebResourceRequest
                            ): Boolean {
                                val url = request.url.toString()
                                return if (url.startsWith("legado://")) {
                                    handleLegado(url)
                                    true
                                } else {
                                    false   // http(s) 继续在 WebView 内加载
                                }
                            }
                        }
                    }
                },
                update = { wv ->
                    wv.loadDataWithBaseURL(null, article.html, "text/html", "utf-8", null)
                }
            )
        }
    }

    importMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { importMsg = null },
            title = { Text(if (importing) "导入中…" else "一键导入") },
            text = { Text(if (importing) "正在下载并识别源…" else msg) },
            confirmButton = {
                TextButton(onClick = { importMsg = null }) { Text("好") }
            }
        )
    }
}
