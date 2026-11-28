package io.lanread.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import io.lanread.app.data.BookEntity
import io.lanread.app.source.SourceRepository
import kotlinx.coroutines.launch

class BookViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val repo = SourceRepository(db, app)
    private val bookDao = db.bookDao()

    var book by mutableStateOf<BookEntity?>(null)
        private set
    /** 预览模式：未入库的候选书（点击搜索/发现结果进入，由用户决定是否加入书架） */
    var previewHit by mutableStateOf<SourceRepository.SearchHit?>(null)
        private set
    var chapters by mutableStateOf<List<SourceRepository.Chapter>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load(bookId: Long) {
        viewModelScope.launch {
            loading = true
            error = null
            previewHit = null
            try {
                val b = bookDao.byId(bookId)
                book = b
                if (b == null) {
                    error = "书籍不存在（可能已被移除）"
                    loading = false
                    return@launch
                }
                val info = repo.bookInfo(b.sourceId, b.uri)
                val tocUrl = info?.tocUrl ?: b.uri
                chapters = repo.toc(b.sourceId, tocUrl)
                if (chapters.isEmpty()) error = tocEmptyMessage()
            } catch (e: Exception) {
                error = "加载失败：${e.message}"
            }
            loading = false
        }
    }

    /** 预览模式加载：只拉详情与目录，不写数据库 */
    fun loadPreview(hit: SourceRepository.SearchHit) {
        viewModelScope.launch {
            loading = true
            error = null
            book = null
            previewHit = hit
            try {
                val info = repo.bookInfo(hit.sourceId, hit.bookUrl)
                val tocUrl = info?.tocUrl ?: hit.bookUrl
                chapters = repo.toc(hit.sourceId, tocUrl)
                if (chapters.isEmpty()) error = tocEmptyMessage()
            } catch (e: Exception) {
                error = "加载失败：${e.message}"
            }
            loading = false
        }
    }

    private fun tocEmptyMessage(): String {
        val note = repo.lastBookInfoNote
        return if (note != null) "目录为空：$note" else "目录为空：书源规则可能不适用于该书"
    }

    /** 加入书架（预览模式点按钮才入库）：返回书架 id 供跳转 */
    suspend fun addPreviewToShelf(): Long? {
        val hit = previewHit ?: return null
        val r = repo.addToShelf(hit)
        previewHit = null
        return r.bookId
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(nav: NavController, bookId: Long, hitArg: SourceRepository.SearchHit? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: BookViewModel = viewModel(factory = viewModelFactory {
        initializer { BookViewModel(app) }
    })
    LaunchedEffect(bookId, hitArg) {
        if (hitArg != null) vm.loadPreview(hitArg) else vm.load(bookId)
    }
    var addedNav by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        vm.book?.title ?: vm.previewHit?.title ?: "书籍详情",
                        fontFamily = LanType.Display,
                        fontSize = 18.sp,
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
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            if (vm.loading) {
                CircularProgressIndicator()
            } else {
                Column(Modifier.fillMaxSize()) {
                    val inPreview = vm.previewHit != null
                    val title = vm.book?.title ?: vm.previewHit?.title
                    if (title != null) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                title,
                                fontFamily = LanType.Display,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            val author = vm.book?.author ?: vm.previewHit?.author ?: ""
                            val srcName = vm.book?.sourceName ?: vm.previewHit?.sourceName ?: ""
                            Text(
                                "${author.ifBlank { "佚名" }} · $srcName",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val intro = vm.book?.intro ?: vm.previewHit?.intro ?: ""
                            if (intro.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    intro,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "共 ${vm.chapters.size} 章" + (vm.book?.let { " · 已读至第 ${it.chapterIndex + 1} 章" } ?: ""),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (inPreview && !addedNav) {
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        if (vm.previewHit == null) return@Button
                                        scope.launch {
                                            val id = vm.addPreviewToShelf()
                                            addedNav = true
                                            if (id != null) {
                                                nav.navigate(Routes.book(id)) {
                                                    popUpTo(Routes.BOOK) { inclusive = true }
                                                }
                                            }
                                        }
                                    }) { Text("加入书架") }
                                }
                            }
                        }
                    }
                    vm.error?.let {
                        Text(
                            it,
                            Modifier.padding(horizontal = 16.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val listState = rememberLazyListState()
                    LaunchedEffect(vm.chapters.size, vm.book?.chapterIndex) {
                        val idx = vm.book?.chapterIndex ?: 0
                        if (vm.chapters.size > 0 && idx > 3) listState.scrollToItem(idx - 3)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        itemsIndexed(vm.chapters, key = { _, c -> c.url }) { i, chapter ->
                            Card(
                                onClick = {
                                    val preview = vm.previewHit
                                    if (preview != null) {
                                        // 预览模式点章节：先入库（阅读器需 bookId 存进度）再进阅读器
                                        scope.launch {
                                            val id = vm.addPreviewToShelf()
                                            if (id != null) {
                                                nav.navigate(Routes.reader(id, i, chapter.url)) {
                                                    popUpTo(Routes.BOOK) { inclusive = true }
                                                }
                                            }
                                        }
                                    } else {
                                        nav.navigate(Routes.reader(bookId, i, chapter.url))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (i == vm.book?.chapterIndex)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Text(
                                    chapter.title,
                                    Modifier.padding(12.dp),
                                    fontSize = 14.sp,
                                    color = if (i == vm.book?.chapterIndex)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
    }
    }
}
