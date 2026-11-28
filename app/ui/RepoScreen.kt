package io.lanread.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import io.lanread.app.data.AppDatabase
import io.lanread.app.source.RssRepository
import io.lanread.app.source.RssSourceParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 源仓库（v0.4.12）：书源页顶栏入口。把订阅源（yck 源仓库类）里带"书源"字样的分组
 * 以仓库列表形式浏览（名称 + 上传者/时间行，图 2 样式），点击条目一键导入书源。
 */
class RepoViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val rssRepo = RssRepository(db, app, io.lanread.app.source.SourceRepository(db, app))

    /** 供浏览的（订阅源 id, 源名, 分组序）三元组：分组名含"书源"且不含"订阅源" */
    data class RepoEntry(val rssId: Long, val rssName: String, val groupIndex: Int, val title: String)

    var repos by mutableStateOf<List<RepoEntry>>(emptyList())
        private set
    var active by mutableStateOf<RepoEntry?>(null)
        private set
    var articles by mutableStateOf<List<RssRepository.Article>>(emptyList())
        private set
    var page by mutableStateOf(1)
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var importing by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val all = db.rssSourceDao().all().firstOrNull() ?: return@launch
            val out = ArrayList<RepoEntry>()
            for (se in all) {
                val src = RssSourceParser.parseSingle(se.json) ?: continue
                src.groups().forEachIndexed { i, g ->
                    if (g.title.contains("书源") && !g.title.contains("订阅源")) {
                        out.add(RepoEntry(se.id, se.name, i, g.title))
                    }
                }
            }
            repos = out
            if (out.isNotEmpty()) select(out.first())
        }
    }

    fun select(e: RepoEntry) {
        active = e
        page = 1
        articles = emptyList()
        message = null
        load()
    }

    fun turnPage(delta: Int) {
        val next = page + delta
        if (next < 1) return
        page = next
        load()
    }

    private fun load() {
        val e = active ?: return
        viewModelScope.launch {
            loading = true
            message = null
            val list = rssRepo.loadGroup(e.rssId, e.groupIndex, page)
            articles = list
            loading = false
            if (list.isEmpty()) {
                val reason = rssRepo.lastEmptyReason
                message = if (reason != null) "本页没有源：$reason"
                else "本页没有源（规则不适用、站点不可达或已到末页）"
            }
        }
    }

    /** 点击一条：从预渲染正文提取 legado:// 一键导入链接（缺失时按 yck 约定把
     *  /content/id/N.html 换成 /json/id/N.json），交给 importAuto 判别入库 */
    fun importOne(article: RssRepository.Article) {
        if (importing) return
        viewModelScope.launch {
            importing = true
            message = null
            val src = Regex("legado://import/auto\\?src=([^\"'&\\s]+)")
                .find(article.html)?.groupValues?.getOrNull(1)
            val url = src?.let { android.net.Uri.decode(it) } ?: run {
                article.link.replace(Regex("/content/id/(\\d+)\\.html"), "/json/id/$1.json")
            }
            message = if (url.isBlank()) {
                "该条目没有可导入的源链接"
            } else {
                rssRepo.importAuto(url)
            }
            importing = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: RepoViewModel = viewModel(factory = viewModelFactory {
        initializer { RepoViewModel(app) }
    })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "源仓库",
                        fontFamily = LanType.Display,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (vm.repos.isEmpty()) {
                vm.message?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 16.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "未发现源仓库。\n先在「订阅源」导入源仓库类订阅源（如 yck），其「书源」分组会在此浏览，点击条目一键导入书源。",
                    Modifier.padding(horizontal = 24.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    rowItems(vm.repos) { entry ->
                        FilterChip(
                            selected = vm.active == entry,
                            onClick = { vm.select(entry) },
                            label = { Text(entry.title, fontSize = 13.sp) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                if (vm.loading) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) { CircularProgressIndicator() }
                } else {
                    vm.message?.let {
                        Text(
                            it,
                            Modifier.padding(horizontal = 16.dp),
                            fontSize = 12.sp,
                            color = if (it.contains("失败") || it.contains("没有"))
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    LazyColumn(
                        Modifier.fillMaxSize().weight(1f),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(vm.articles, key = { i, a -> "$i:${a.link}:${a.title}" }) { i, article ->
                            Card(
                                onClick = { vm.importOne(article) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (i % 2 == 0) MaterialTheme.colorScheme.surface
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Text(
                                        article.title,
                                        fontFamily = LanType.Display,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (article.pubDate.isNotBlank()) {
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            article.pubDate,
                                            fontFamily = LanType.Folio,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { vm.turnPage(-1) }, enabled = vm.page > 1) {
                            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上一页")
                        }
                        Text(
                            "${vm.page}",
                            fontFamily = LanType.Folio,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(onClick = { vm.turnPage(1) }) {
                            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下一页")
                        }
                    }
                }
            }
        }
    }
}
