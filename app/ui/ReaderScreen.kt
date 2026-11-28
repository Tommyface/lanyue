package io.lanread.app.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import coil.compose.AsyncImage
import io.lanread.app.data.AppDatabase
import io.lanread.app.data.BookEntity
import io.lanread.app.data.Prefs
import io.lanread.app.reader.Paginator
import io.lanread.app.reader.TxtCodec
import io.lanread.app.source.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 系统 TTS 朗读控制器：长文本按句切分入队，本页读完回调自动续读。
 */
class TtsHolder(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    var ready = false
    var listening by mutableStateOf(false)
    var onUnavailable: (() -> Unit)? = null
    var onPageDone: (() -> Unit)? = null

    init {
        engine = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.SIMPLIFIED_CHINESE
                ready = true
            }
        }
    }

    fun speak(text: String) {
        val e = engine
        if (e == null || !ready) {
            onUnavailable?.invoke()
            return
        }
        e.stop()
        val chunks = splitChunks(text)
        if (chunks.isEmpty()) {
            onPageDone?.invoke()
            return
        }
        val lastId = "lanread-${chunks.size - 1}"
        e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) {}
            override fun onDone(id: String?) {
                if (id == lastId) {
                    handler.post { if (listening) onPageDone?.invoke() }
                }
            }
        })
        chunks.forEachIndexed { i, c -> e.speak(c, TextToSpeech.QUEUE_ADD, null, "lanread-$i") }
        listening = true
    }

    fun stop() {
        engine?.stop()
        listening = false
    }

    fun release() {
        engine?.let {
            it.stop()
            it.shutdown()
        }
        engine = null
        listening = false
    }

    /** 按中文句末标点切分，避免超长 utterance 被系统截断 */
    private fun splitChunks(text: String, max: Int = 400): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n' || sb.length >= max) {
                out.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}

class ReaderViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val repo = SourceRepository(db, app)
    private val bookDao = db.bookDao()

    var content by mutableStateOf("")
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var book by mutableStateOf<BookEntity?>(null)
        private set

    /** v0.4.19 图片解密：imageDecode 规则存在时，图片 URL → 解密后本地 file:// 的映射 */
    var imageUrls by mutableStateOf<List<String>>(emptyList())
        private set

    /** v0.4.30：连续阅读——目录列表 + 当前章节索引 + 章节标题 */
    var chapters by mutableStateOf<List<SourceRepository.Chapter>>(emptyList())
        private set
    var currentChapter by mutableStateOf(0)
        private set
    var currentChapterTitle by mutableStateOf("")
        private set

    fun load(bookId: Long, chapter: Int, url: String) {
        viewModelScope.launch {
            loading = true
            error = null
            currentChapter = chapter
            try {
                val b = bookDao.byId(bookId)
                book = b
                if (b == null) {
                    error = "书籍不存在（可能已被移除）"
                    loading = false
                    return@launch
                }
                content = if (b.type == 0) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            getApplication<android.app.Application>().contentResolver
                                .openInputStream(android.net.Uri.parse(b.uri))
                                ?.use { it.readBytes() }
                                ?.let { TxtCodec.decode(it) }
                        }.getOrNull() ?: ""
                    }
                } else {
                    repo.content(b.sourceId, url)
                }
                if (content.isBlank()) error = "正文为空：书源规则未能提取内容"
                // v0.4.19：漫画图片解密管线（imageDecode 规则）
                if (b.type == 2 && content.contains("<img")) {
                    imageUrls = withContext(Dispatchers.IO) {
                        repo.decryptImages(b.sourceId, content)
                    }
                }
                // v0.4.30：异步加载目录用于连续阅读
                if (b.type != 0) loadTocAsync(b)
            } catch (e: Exception) {
                error = "加载失败：${e.message}"
            }
            loading = false
        }
    }

    /** v0.4.30：异步加载目录（不阻塞正文显示） */
    private fun loadTocAsync(b: BookEntity) {
        viewModelScope.launch {
            try {
                val info = repo.bookInfo(b.sourceId, b.uri)
                val tocUrl = info?.tocUrl ?: b.uri
                chapters = repo.toc(b.sourceId, tocUrl)
                currentChapterTitle = chapters.getOrNull(currentChapter)?.title ?: ""
            } catch (_: Exception) {}
        }
    }

    /** v0.4.30：连续阅读——加载下一章 */
    fun loadNextChapter() {
        val nextIdx = currentChapter + 1
        if (nextIdx >= chapters.size) return
        val next = chapters[nextIdx]
        currentChapter = nextIdx
        loading = true  // 立即显示加载态（不等 launch）
        error = null
        viewModelScope.launch {
            try {
                content = repo.content(book?.sourceId ?: 0, next.url)
                currentChapterTitle = next.title
                if (content.isBlank()) error = "正文为空：书源规则未能提取内容"
                if (book?.type == 2 && content.contains("<img")) {
                    imageUrls = withContext(Dispatchers.IO) {
                        repo.decryptImages(book?.sourceId ?: 0, content)
                    }
                }
            } catch (e: Exception) {
                error = "加载失败：${e.message}"
            }
            loading = false
        }
    }

    fun saveProgress(bookId: Long, chapter: Int, page: Int) {
        viewModelScope.launch {
            val b = bookDao.byId(bookId) ?: return@launch
            bookDao.update(
                b.copy(chapterIndex = chapter, page = page, lastReadAt = System.currentTimeMillis())
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    nav: NavController,
    bookId: Long,
    chapterArg: Int,
    urlArg: String
) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: ReaderViewModel = viewModel(factory = viewModelFactory {
        initializer { ReaderViewModel(app) }
    })
    LaunchedEffect(bookId, chapterArg, urlArg) { vm.load(bookId, chapterArg, urlArg) }

    var fontSize by remember { mutableStateOf(Prefs.fontSize(context)) }
    var themeIndex by remember { mutableStateOf(Prefs.readerTheme(context)) }
    var pageMode by remember { mutableStateOf(Prefs.pageMode(context)) }  // 0=左右翻页 1=上下滚动
    var menuOpen by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var restored by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }  // v0.4.30：到达末页标志（连续阅读用）
    var ttsMsg by remember { mutableStateOf<String?>(null) }
    val isComic = vm.book?.type == 2
    val isVertical = !isComic && pageMode == 1  // 上下滚动模式

    val theme = ReaderThemes[themeIndex.coerceIn(0, ReaderThemes.size - 1)]

    val ttsHolder = remember { TtsHolder(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { ttsHolder.release() }
    }

    Scaffold(
        containerColor = theme.bg,
        topBar = {
            if (menuOpen) {
                TopAppBar(
                    title = {
                        Text(
                            vm.book?.title ?: "正在阅读",
                            color = theme.text,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = theme.text)
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = false }) {
                            Icon(Icons.AutoMirrored.Filled.List, "收起菜单", tint = theme.dim)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.bg)
                )
            }
        }
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(theme.bg)
                .pointerInput(vm.content, isComic, pageMode) {
                    detectTapGestures(
                        onDoubleTap = {
                            // v0.4.29：双击正文弹出设置菜单
                            if (!isComic) menuOpen = !menuOpen
                        },
                        onTap = { offset ->
                            val w = size.width
                            when {
                                isComic -> menuOpen = !menuOpen
                                isVertical -> { /* 上下滚动模式：单击不翻页，滚动即阅读 */ }
                                offset.x < w / 3f -> {
                                    ttsHolder.stop()
                                    if (page > 0) page--
                                }
                                offset.x > w * 2f / 3f -> {
                                    ttsHolder.stop()
                                    if (!reachedEnd) {
                                        page++
                                    } else {
                                        // v0.4.30：翻到末页再翻 → 自动加载下一章
                                        vm.loadNextChapter()
                                    }
                                }
                            }
                        }
                    )
                }
        ) {
            val density = LocalDensity.current
            val textWidth = (maxWidth - 32.dp).coerceAtLeast(100.dp)
            val textHeight = (maxHeight - 40.dp).coerceAtLeast(100.dp)
            val widthPx = with(density) { textWidth.toPx() }.toInt()
            val heightPx = with(density) { textHeight.toPx() }.toInt()
            val paint = remember(fontSize, density.density) {
                TextPaint().apply {
                    textSize = fontSize * density.density
                    typeface = Typeface.DEFAULT
                }
            }
            // v0.4.28：正文净化——书源正文常带 <p>/<br> HTML 标签（截图实锤裸露显示）。
            // 非漫画源在分页前净化成纯文本+换行（漫画源保留 <img> HTML 走图片渲染）。
            val displayContent = if (isComic) vm.content else sanitizeHtml(vm.content)
            // v0.4.29：上下滚动模式不分页，直接全文字滚动；左右翻页模式保留 Paginator 分页
            val pages = if (!isVertical && !isComic) {
                remember(displayContent, fontSize, widthPx, heightPx) {
                    Paginator.paginate(displayContent, widthPx, heightPx, paint, 1.6f)
                }
            } else emptyList()

            // v0.4.30：检测到达末页（连续阅读触发条件）
            LaunchedEffect(page, pages.size) {
                reachedEnd = !isVertical && !isComic && pages.isNotEmpty() && page >= pages.size - 1
            }

            fun pageTextAt(i: Int): String {
                if (pages.isEmpty()) return ""
                val idx = i.coerceIn(0, pages.size - 1)
                // v0.4.28：分页基于 displayContent（净化后），取页必须同源，否则净化后长度错位
                return displayContent.substring(pages[idx].start, pages[idx].end)
            }

            // v0.4.30：章节切换时重置到第一页 + 重置末页标志
            LaunchedEffect(vm.currentChapter) {
                page = 0
                restored = false
                reachedEnd = false
            }
            // 首次加载恢复页级进度（漫画无页级进度）
            LaunchedEffect(vm.content) {
                if (vm.content.isNotBlank() && !restored) {
                    restored = true
                    val b = vm.book
                    if (b != null && b.page > 0 && b.type != 2) {
                        page = b.page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    }
                }
            }
            // 翻页时持久化进度
            LaunchedEffect(page) {
                if (restored && vm.content.isNotBlank()) {
                    vm.saveProgress(bookId, chapterArg, page)
                }
            }

            // TTS：水平模式本页读完自动续读下一页；垂直模式全文读完即停
            ttsHolder.onPageDone = {
                if (isVertical) {
                    ttsHolder.listening = false
                } else if (page < pages.size - 1) {
                    page += 1
                    ttsHolder.speak(pageTextAt(page))
                } else {
                    ttsHolder.listening = false
                }
            }
            ttsHolder.onUnavailable = {
                ttsMsg = "系统 TTS 未就绪或无中文语音数据（可在系统设置中安装/启用语音引擎）"
            }

            if (vm.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (vm.error != null) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(vm.error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { nav.popBackStack() }) { Text("返回") }
                }
            } else if (isComic) {
                // 漫画模式：纵向连续滚动图片（条漫）；视频条目占位卡可点击进播放页
                // v0.4.19：优先用解密管线产物（imageDecode 源），无则现场解析 <img>
                val imgs = if (vm.imageUrls.isNotEmpty()) vm.imageUrls else parseImgUrls(vm.content)
                ComicView(
                    images = imgs,
                    modifier = Modifier.fillMaxSize(),
                    onVideo = { url -> nav.navigate(Routes.video(url, vm.book?.title ?: "")) }
                )
            } else if (isVertical) {
                // v0.4.29：上下滚动模式——全文字连续滚动，自动换行由 Text 渲染保证
                Text(
                    displayContent,
                    color = theme.text,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.6f).sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                Text(
                    pageTextAt(page),
                    color = theme.text,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.6f).sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                ) {
                    if (vm.currentChapterTitle.isNotBlank()) {
                        Text(
                            vm.currentChapterTitle,
                            color = theme.dim,
                            fontSize = 10.sp,
                            fontFamily = LanType.Folio,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        "${page.coerceIn(0, (pages.size - 1).coerceAtLeast(0)) + 1} / ${pages.size}",
                        color = theme.dim,
                        fontSize = 11.sp,
                        fontFamily = LanType.Folio
                    )
                }
            }

            ttsMsg?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp)
                )
            }

            if (menuOpen) {
                ReaderMenu(
                    fontSize = fontSize,
                    onFontSize = { v ->
                        fontSize = v
                        Prefs.setFontSize(context, v)
                    },
                    themeIndex = themeIndex,
                    onTheme = { i ->
                        themeIndex = i
                        Prefs.setReaderTheme(context, i)
                    },
                    pageMode = pageMode,
                    onPageMode = { v ->
                        pageMode = v
                        Prefs.setPageMode(context, v)
                    },
                    showTts = !isComic,
                    listening = ttsHolder.listening,
                    onPlay = {
                        ttsMsg = null
                        ttsHolder.speak(if (isVertical) displayContent else pageTextAt(page))
                    },
                    onStop = { ttsHolder.stop() },
                    onClose = { menuOpen = false },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                )
            }
        }
    }
}

/**
 * v0.4.28：正文 HTML 净化——书源正文常带 <p>/<br> 等标签（截图实锤裸露显示）。
 * 块级标签转换行、剥其余标签、解码常见 HTML 实体，输出纯文本供 Paginator 分页与 Text 渲染。
 * 参照 Legado ContentProcessor 的 replaceContent 净化语义（块级标签→\n）。
 */
private fun sanitizeHtml(html: String): String {
    if (html.isBlank()) return html
    var s = html
    // <br> 与块级闭合/开标签 → 换行
    s = s.replace(Regex("(?i)\\s*<\\s*br\\s*/?\\s*>"), "\n")
    s = s.replace(Regex("(?i)<\\s*/\\s*(p|div|section|article|li|h[1-6]|blockquote|tr|td|th)\\s*>"), "\n")
    s = s.replace(Regex("(?i)<\\s*(p|div|section|article|li|h[1-6]|blockquote|tr|td|th)\\b[^>]*>"), "\n")
    // 剥其余标签
    s = s.replace(Regex("<[^>]+>"), "")
    // 常见 HTML 实体解码
    s = s.replace("&nbsp;", " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&hellip;", "…").replace("&mdash;", "—").replace("&ndash;", "–")
        .replace("&ldquo;", "\u201C").replace("&rdquo;", "\u201D")
        .replace("&lsquo;", "\u2018").replace("&rsquo;", "\u2019")
    s = s.replace(Regex("&#(\\d+);")) { mr ->
        val code = mr.groupValues[1].toIntOrNull() ?: return@replace ""
        if (code in 0x20..0x10FFFF) code.toChar().toString() else ""
    }
    s = s.replace(Regex("&[a-zA-Z]+;"), "") // 未识别实体剥掉，避免残留 &xxx;
    // 压缩 3+ 连续空行为段间距
    s = s.replace(Regex("\n{3,}"), "\n\n")
    // v0.4.30：段落首行缩进（中文排版习惯）——每段首行加两个全角空格
    s = s.split("\n").joinToString("\n") { para ->
        if (para.isBlank()) para else "\u3000\u3000" + para.trimStart()
    }
    return s.trim()
}

@Composable
private fun ReaderMenu(
    fontSize: Int,
    onFontSize: (Int) -> Unit,
    themeIndex: Int,
    onTheme: (Int) -> Unit,
    pageMode: Int,
    onPageMode: (Int) -> Unit,
    showTts: Boolean,
    listening: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("阅读设置", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        "收起",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("字号", fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { if (fontSize > 12) onFontSize(fontSize - 1) }) {
                    Text("A－", fontSize = 14.sp)
                }
                Text("$fontSize", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { if (fontSize < 32) onFontSize(fontSize + 1) }) {
                    Text("A＋", fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderThemes.forEachIndexed { i, t ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(32.dp)
                            .background(t.bg, MaterialTheme.shapes.small)
                            .clickable { onTheme(i) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            t.name,
                            fontSize = 12.sp,
                            color = if (i == themeIndex) t.text else t.dim
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("翻页方式", fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "左右翻页", 1 to "上下滚动").forEach { (mode, label) ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (pageMode == mode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { onPageMode(mode) }
                        ) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = if (pageMode == mode) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (showTts) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("听书", fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onPlay, enabled = !listening) {
                        Text(if (listening) "朗读中…" else "播放本页")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onStop, enabled = listening) {
                        Text("停止")
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    !showTts -> "漫画：上下滚动阅读，双击弹出菜单"
                    pageMode == 0 -> "左右翻页：左 1/3 上一页｜右 1/3 下一页｜双击弹出设置"
                    else -> "上下滚动：滑动阅读，双击弹出设置"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 漫画阅读：解析 <img> 列表，Coil 纵向连续滚动（条漫式体验）
 *  v0.4.17：视频源章节（tiktokcdn mp4 等）混入时给出占位卡片而非报错崩溃
 *  v0.4.18：视频占位卡可点击进全屏 WebView 播放页 */
@Composable
private fun ComicView(
    images: List<String>,
    modifier: Modifier = Modifier,
    onVideo: (String) -> Unit = {}
) {
    val listState = rememberLazyListState()
    if (images.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painterResource(io.lanread.app.R.drawable.ic_comic_placeholder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "没有解析到图片（源规则未返回图片列表）",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(images.size) { i ->
            val url = images[i]
            if (url.isVideoLike()) {
                // 视频条目（如源把 tiktokcdn mp4 混进图片列表）：可点击的占位卡片
                // v0.4.23：自绘视频占位矢量（播放圆窗+水波进度）替代 emoji
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp)
                        .clickable { onVideo(url) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painterResource(io.lanread.app.R.drawable.ic_video_placeholder),
                            contentDescription = "视频章节",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "视频内容（第 ${i + 1} 项），点击播放",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                AsyncImage(
                    model = images[i],
                    contentDescription = "第${i + 1}张",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 视频链接甄别：mp4/ts/m3u8/mime_type=video 或常见视频 CDN 路径特征 */
private fun String.isVideoLike(): Boolean {
    val u = this.substringBefore('?')
    return this.contains("mime_type=video") ||
        u.endsWith(".mp4") || u.endsWith(".ts") || u.endsWith(".m3u8") ||
        u.contains("/video/tos/")
}

private fun parseImgUrls(html: String): List<String> {
    val out = ArrayList<String>()
    for (m in Regex("""<img[^>]+src=["']([^"']+)["']""").findAll(html)) {
        out.add(m.groupValues[1])
    }
    // v0.4.22：视频直链章节标记（content() 产 <video-src>URL</video-src>）→ 占位卡播放
    for (m in Regex("""<video-src>([^<]+)</video-src>""").findAll(html)) {
        out.add(m.groupValues[1])
    }
    return out
}
