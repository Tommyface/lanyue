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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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

    fun load(bookId: Long, chapter: Int, url: String) {
        viewModelScope.launch {
            loading = true
            error = null
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
    var menuOpen by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var restored by remember { mutableStateOf(false) }
    var ttsMsg by remember { mutableStateOf<String?>(null) }
    val isComic = vm.book?.type == 2

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
                .pointerInput(vm.content, isComic) {
                    detectTapGestures { offset ->
                        val w = size.width
                        when {
                            isComic -> menuOpen = !menuOpen
                            offset.x < w / 3f -> {
                                ttsHolder.stop()
                                if (page > 0) page--
                            }
                            offset.x > w * 2f / 3f -> {
                                ttsHolder.stop()
                                page++
                            }
                            else -> menuOpen = !menuOpen
                        }
                    }
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
            val pages = remember(vm.content, fontSize, widthPx, heightPx) {
                Paginator.paginate(vm.content, widthPx, heightPx, paint, 1.6f)
            }

            fun pageTextAt(i: Int): String {
                if (pages.isEmpty()) return ""
                val idx = i.coerceIn(0, pages.size - 1)
                return vm.content.substring(pages[idx].start, pages[idx].end)
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

            // TTS：本页读完自动续读下一页
            ttsHolder.onPageDone = {
                if (page < pages.size - 1) {
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
                Text(
                    "${page.coerceIn(0, (pages.size - 1).coerceAtLeast(0)) + 1} / ${pages.size}",
                    color = theme.dim,
                    fontSize = 11.sp,
                    fontFamily = LanType.Folio,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                )
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
                    showTts = !isComic,
                    listening = ttsHolder.listening,
                    onPlay = {
                        ttsMsg = null
                        ttsHolder.speak(pageTextAt(page))
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

@Composable
private fun ReaderMenu(
    fontSize: Int,
    onFontSize: (Int) -> Unit,
    themeIndex: Int,
    onTheme: (Int) -> Unit,
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
                if (showTts)
                    "左 1/3 屏上一页｜右 1/3 屏下一页｜中间：菜单\n听书从当前页开始，本页读完自动续读下一页"
                else
                    "漫画：上下滚动阅读，点击屏幕呼出菜单",
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
            Text(
                "没有解析到图片（源规则未返回图片列表）",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp)
                        .clickable { onVideo(url) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎬", fontSize = 26.sp)
                        Spacer(Modifier.height(4.dp))
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
    return out
}
