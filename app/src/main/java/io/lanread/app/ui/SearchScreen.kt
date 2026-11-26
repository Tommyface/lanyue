package io.lanread.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.lanread.app.source.SourceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SearchViewModel(app: android.app.Application) : AndroidViewModel(app) {
    val repo = SourceRepository(AppDatabase.get(app), app)

    var results by mutableStateOf<List<SourceRepository.SearchHit>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var sourcesCount by mutableStateOf(0)
        private set
    private var job: Job? = null

    init {
        viewModelScope.launch {
            repo.sourcesFlow().collect { list ->
                sourcesCount = list.count { it.enabled }
            }
    }
    }

    fun search(keyword: String) {
        if (keyword.isBlank()) return
        job?.cancel()
        searching = true
        message = null
        results = emptyList()
        job = viewModelScope.launch {
            try {
                val hits = repo.searchAll(keyword)
                results = hits
                searching = false
                if (hits.isEmpty()) message = "没有搜到结果：书源未导入、全部禁用或全部失败"
            } catch (e: Exception) {
                searching = false
                message = "搜索失败：${e.message}"
            }
        }
    }

    fun addToShelf(hit: SourceRepository.SearchHit, done: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val r = repo.addToShelf(hit)
                done(if (r.existed) "书架上已有《${hit.title}》，无需重复添加" else "《${hit.title}》已加入书架")
            } catch (e: Exception) {
                done("入库失败：${e.message}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: SearchViewModel = viewModel(factory = viewModelFactory {
        initializer { SearchViewModel(app) }
    })
    val keyword = remember { mutableStateOf("") }
    var addingMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "搜索书源",
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
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = keyword.value,
                onValueChange = { keyword.value = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("书名 / 关键词") },
                trailingIcon = {
                    if (keyword.value.isNotEmpty()) {
                        IconButton(onClick = { keyword.value = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )
            Spacer(Modifier.height(8.dp))
            Row1(
                text = "搜索",
                enabled = !vm.searching,
                onClick = { vm.search(keyword.value.trim()) },
                icon = { Icon(Icons.Filled.Search, contentDescription = null) }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "启用书源 ${vm.sourcesCount} 条 · ${if (vm.searching) "搜索中..." else "结果 ${vm.results.size} 条"}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (vm.searching) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            vm.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(vm.results, key = { "${it.sourceId}::${it.bookUrl}" }) { hit ->
                    SearchHitCard(hit = hit, onClick = {
                        // 预览模式：进详情页由用户决定是否加入书架（不自动入库）
                        nav.navigate(Routes.preview(hit))
                    })
                }
            }
        }
    }

    addingMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { addingMsg = null },
            title = { Text("加入书架") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    addingMsg = null
                    nav.popBackStack()
                }) { Text("回到书架") }
            }
        )
    }
}

@Composable
private fun Row1(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit = {}
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    ) {
        if (enabled.not()) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(8.dp))
        }
        icon()
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 16.sp)
    }
}

@Composable
private fun SearchHitCard(hit: SourceRepository.SearchHit, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                hit.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append(hit.author.ifBlank { "佚名" })
                        if (hit.latestChapter.isNotBlank()) append(" · ").append(hit.latestChapter)
                        append(" · 来自 ").append(hit.sourceName)
                    },
                    fontSize = 12.sp,
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
