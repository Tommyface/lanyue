package io.lanread.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import io.lanread.app.data.SourceEntity
import io.lanread.app.source.ExploreItem
import io.lanread.app.source.SourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExploreViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val repo = SourceRepository(db, app)

    /** 有 exploreUrl 的启用书源 */
    val sources = MutableStateFlow<List<SourceEntity>>(emptyList())

    var activeSourceId by mutableStateOf<Long?>(null)
        private set
    var entries by mutableStateOf<List<ExploreItem>>(emptyList())
        private set
    var entryIndex by mutableStateOf(0)
        private set
    var page by mutableStateOf(1)
        private set
    var hits by mutableStateOf<List<SourceRepository.SearchHit>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            val list = repo.exploreSources()
            sources.value = list
            if (list.isNotEmpty()) selectSource(list.first().id)
        }
    }

    fun selectSource(id: Long?) {
        activeSourceId = id
        entries = emptyList()
        entryIndex = 0
        page = 1
        hits = emptyList()
        message = null
        if (id != null) {
            viewModelScope.launch {
                loading = true
                val es = repo.exploreEntries(id)
                entries = es
                loading = false
                if (es.isEmpty()) {
                    message = "该源没有发现页入口（exploreUrl 为空或解析失败）"
                } else {
                    loadPage()
                }
            }
        }
    }

    fun selectEntry(i: Int) {
        entryIndex = i
        page = 1
        hits = emptyList()
        loadPage()
    }

    fun turnPage(delta: Int) {
        val next = page + delta
        if (next < 1) return
        page = next
        loadPage()
    }

    private fun loadPage() {
        val sid = activeSourceId ?: return
        val entry = entries.getOrNull(entryIndex) ?: return
        viewModelScope.launch {
            loading = true
            message = null
            val list = repo.explorePage(sid, entry.url, page)
            hits = list
            loading = false
            if (list.isEmpty()) {
                val reason = repo.lastEmptyReason
                message = if (reason != null) "本页没有内容：$reason"
                else "本页没有内容（规则不适用、站点不可达或已到末页）"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: ExploreViewModel = viewModel(factory = viewModelFactory {
        initializer { ExploreViewModel(app) }
    })
    val scope = rememberCoroutineScope()
    val sources by vm.sources.collectAsState()
    val activeSource = sources.firstOrNull { it.id == vm.activeSourceId }
    var sourceMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        activeSource?.name ?: "发现",
                        fontFamily = LanType.Display,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (sources.size > 1) {
                        Box {
                            IconButton(onClick = { sourceMenuOpen = true }) {
                                Icon(Icons.Filled.Menu, contentDescription = "切换源")
                            }
                            DropdownMenu(
                                expanded = sourceMenuOpen,
                                onDismissRequest = { sourceMenuOpen = false }
                            ) {
                                sources.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(s.name, fontSize = 14.sp) },
                                        onClick = {
                                            sourceMenuOpen = false
                                            vm.selectSource(s.id)
                                        }
                                    )
                                }
                            }
                        }
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
            // 分组 Tab（动态标签如 漫蛙的 全部/3D/CG/…）
            if (vm.entries.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    rowItems(vm.entries) { entry ->
                        FilterChip(
                            selected = vm.entries.indexOf(entry) == vm.entryIndex,
                            onClick = { vm.selectEntry(vm.entries.indexOf(entry)) },
                            label = { Text(entry.title, fontSize = 13.sp) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }

            if (vm.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (activeSource == null) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "没有可发现的源",
                        fontFamily = LanType.Display,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "当前启用书源均未提供 exploreUrl 发现入口。\n导入带发现页的书源（如漫画源）后在此浏览分类。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                vm.message?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 16.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(vm.hits, key = { "${it.sourceId}::${it.bookUrl}::${it.title}" }) { hit ->
                        ExploreCard(hit = hit, onClick = {
                            // 预览模式：进详情页由用户决定是否加入书架（不自动入库）
                            nav.navigate(Routes.preview(hit))
                        })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
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

@Composable
private fun ExploreCard(hit: SourceRepository.SearchHit, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(12.dp)) {
            // 方形圆角封面（图 1 样式）：无封面或加载失败回退书名首字底纹
            Box(
                Modifier
                    .width(78.dp)
                    .height(78.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (hit.coverUrl.isNotBlank()) {
                    coil.compose.AsyncImage(
                        model = hit.coverUrl,
                        contentDescription = "封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        hit.title.take(1),
                        fontFamily = LanType.Display,
                        fontSize = 28.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    hit.title,
                    fontFamily = LanType.Display,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "作者：" + hit.author.ifBlank { "佚名" },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    StatusTag(hit.kind)
                }
                if (hit.intro.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hit.intro,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
