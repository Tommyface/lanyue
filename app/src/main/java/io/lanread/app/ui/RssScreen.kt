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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.lanread.app.source.LanJs
import io.lanread.app.source.RssRepository
import io.lanread.app.source.RssSourceParser
import io.lanread.app.source.SourceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RssViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val repo = RssRepository(db, app, SourceRepository(db, app))

    init {
        LanJs.ensure(app)
    }

    val sources = repo.sourcesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    var activeSourceId by mutableStateOf<Long?>(null)
        private set
    var groupIndex by mutableStateOf(0)
        private set
    var page by mutableStateOf(1)
        private set
    var articles by mutableStateOf<List<RssRepository.Article>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    var importMessage by mutableStateOf<String?>(null)
        private set
    var importing by mutableStateOf(false)

    // ---------- 源变量（与书源共享 LanJs 全局桥） ----------
    var vars by mutableStateOf<Map<String, String?>>(emptyMap())
        private set

    fun refreshVars() {
        vars = LanJs.java.all()
    }

    fun putVar(k: String, v: String) {
        LanJs.java.put(k, v)
        refreshVars()
    }

    fun removeVar(k: String) {
        LanJs.java.remove(k)
        refreshVars()
    }

    /** 订阅源搜索关键词（source.getVariable() 读的就是这个） */
    fun setSourceVariable(v: String) {
        LanJs.source.setVariable(v)
        refreshVars()
    }

    fun sourceVariable(): String = LanJs.source.getVariable()

    fun selectSource(id: Long?) {
        activeSourceId = id
        groupIndex = 0
        page = 1
        articles = emptyList()
        if (id != null) load()
    }

    fun selectGroup(i: Int) {
        groupIndex = i
        page = 1
        if (activeSourceId != null) load()
    }

    fun turnPage(delta: Int) {
        val next = page + delta
        if (next < 1) return
        page = next
        load()
    }

    private fun load() {
        val sid = activeSourceId ?: return
        viewModelScope.launch {
            loading = true
            message = null
            val list = repo.loadGroup(sid, groupIndex, page)
            articles = list
            loading = false
            if (list.isEmpty()) {
                val reason = repo.lastEmptyReason
                message = if (reason != null) "本页没有文章：$reason"
                else "本页没有文章（分组规则不适用、站点不可达或已到末页）"
            }
        }
    }

    fun import(text: String) {
        if (text.isBlank()) return
        importing = true
        viewModelScope.launch {
            val r = repo.import(text)
            importMessage = r.summary
            importing = false
        }
    }

    fun clearImportMessage() { importMessage = null }

    fun delete(id: Long) {
        viewModelScope.launch {
            repo.deleteSource(id)
            if (activeSourceId == id) {
                activeSourceId = null
                articles = emptyList()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: RssViewModel = viewModel(factory = viewModelFactory {
        initializer { RssViewModel(app) }
    })
    val sources by vm.sources.collectAsState()

    val list = sources
    LaunchedEffect(list) {
        if (vm.activeSourceId == null && !list.isNullOrEmpty()) {
            vm.selectSource(list.first().id)
        }
    }

    val activeSource = sources?.firstOrNull { it.id == vm.activeSourceId }
    val groups = remember(activeSource) {
        activeSource?.let { RssSourceParser.parseSingle(it.json)?.groups() } ?: emptyList()
    }

    var importOpen by remember { mutableStateOf(false) }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var deletePickerOpen by remember { mutableStateOf(false) }
    var varsOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        activeSource?.name ?: "订阅",
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
                    if ((sources?.size ?: 0) > 1) {
                        Box {
                            IconButton(onClick = { sourceMenuOpen = true }) {
                                Text("源", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            DropdownMenu(
                                expanded = sourceMenuOpen,
                                onDismissRequest = { sourceMenuOpen = false }
                            ) {
                                sources?.forEach { s ->
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
                    if (!sources.isNullOrEmpty()) {
                        IconButton(onClick = { deletePickerOpen = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除订阅源")
                        }
                    }
                    IconButton(onClick = { varsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "源变量")
                    }
                    IconButton(onClick = { importOpen = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "导入订阅源")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (groups.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    groups.forEachIndexed { i, g ->
                        FilterChip(
                            selected = i == vm.groupIndex,
                            onClick = { vm.selectGroup(i) },
                            label = { Text(g.title, fontSize = 13.sp) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(12.dp))
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
                        "还没有订阅源",
                        fontFamily = LanType.Display,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点右上角 + 粘贴 Legado 订阅源 JSON 或其 URL 导入。\n导入后可分组浏览文章，正文内可一键导回书源。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
            } else {
                vm.message?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                }
                LazyColumn(
                    Modifier.fillMaxSize().weight(1f),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(vm.articles, key = { i, a -> "$i:${a.link}:${a.title}" }) { i, article ->
                        Card(
                            onClick = {
                                RssSession.articles = vm.articles
                                RssSession.title = activeSource.name
                                nav.navigate("rssArticle/$i")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    article.title,
                                    fontFamily = LanType.Display,
                                    fontSize = 16.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    maxLines = 2,
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

    if (importOpen) {
        val input = remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importOpen = false; vm.clearImportMessage() },
            title = { Text("导入订阅源") },
            text = {
                Column {
                    OutlinedTextField(
                        value = input.value,
                        onValueChange = { input.value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        placeholder = { Text("粘贴订阅源 JSON，或其 URL（超长内容可在框内滚动）") },
                        minLines = 3
                    )
                    vm.importMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            fontSize = 12.sp,
                            color = if (it.startsWith("导入成功"))
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.import(input.value.trim()) }, enabled = !vm.importing) {
                    Text(if (vm.importing) "导入中…" else "导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { importOpen = false; vm.clearImportMessage() }) { Text("取消") }
            }
        )
    }

    if (deletePickerOpen && !sources.isNullOrEmpty()) {
        AlertDialog(
            onDismissRequest = { deletePickerOpen = false },
            title = { Text("删除订阅源") },
            text = {
                Column {
                    sources?.forEach { s ->
                        TextButton(onClick = {
                            vm.delete(s.id)
                            deletePickerOpen = false
                        }) {
                            Text("「${s.name}」", fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { deletePickerOpen = false }) { Text("取消") }
            }
        )
    }

    if (varsOpen) {
        RssVarsDialog(vm = vm, onDismiss = { varsOpen = false })
    }
}

/**
 * 订阅源源变量对话框（LanJs 全局桥，与书源共享）：
 * ① source.getVariable()（搜索分组关键词 keys={{source.getVariable()}}）
 * ② 全局变量存（java.put/get）查看/增删改
 */
@Composable
private fun RssVarsDialog(vm: RssViewModel, onDismiss: () -> Unit) {
    val searchKey = remember { mutableStateOf(vm.sourceVariable()) }
    val newKey = remember { mutableStateOf("") }
    val newVal = remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refreshVars() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "源变量",
                fontFamily = LanType.Display,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "搜索关键词（source.getVariable()）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = searchKey.value,
                    onValueChange = { searchKey.value = it },
                    placeholder = { Text("输入后点应用，再切到搜索分组") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(onClick = {
                            vm.setSourceVariable(searchKey.value)
                        }) { Text("应用") }
                    }
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "全局变量（${vm.vars.size}）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                if (vm.vars.isEmpty()) {
                    Text(
                        "（空）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    vm.vars.toSortedMap().forEach { (k, v) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(k, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    v ?: "（空值）",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { vm.removeVar(k) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除变量",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newKey.value,
                        onValueChange = { newKey.value = it },
                        label = { Text("变量名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value = newVal.value,
                        onValueChange = { newVal.value = it },
                        label = { Text("值") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (newKey.value.isNotBlank()) {
                                vm.putVar(newKey.value.trim(), newVal.value)
                                newKey.value = ""
                                newVal.value = ""
                            }
                        }
                    ) { Text("＋", fontSize = 18.sp) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "说明：变量与书源共享（同一全局存）；\n订阅源搜索分组的 keys={{source.getVariable()}} 即读「搜索关键词」。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
