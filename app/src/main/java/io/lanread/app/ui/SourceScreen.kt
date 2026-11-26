package io.lanread.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import io.lanread.app.data.SourceEntity
import io.lanread.app.source.Analyzer
import io.lanread.app.source.BookSourceParser
import io.lanread.app.source.LoginUiItem
import io.lanread.app.source.LoginUiParser
import io.lanread.app.source.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SourceViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = SourceRepository(AppDatabase.get(app), app)

    init {
        io.lanread.app.source.LanJs.ensure(app)
    }

    val sources = repo.sourcesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    var importMessage by mutableStateOf<String?>(null)
    var importing by mutableStateOf(false)
        private set

    // ---------- 源变量 / 登录页 ----------
    var vars by mutableStateOf<Map<String, String?>>(emptyMap())
        private set
    var actionMessage by mutableStateOf<String?>(null)
        private set

    fun refreshVars() {
        vars = io.lanread.app.source.LanJs.java.all()
    }

    fun putVar(k: String, v: String) {
        io.lanread.app.source.LanJs.java.put(k, v)
        refreshVars()
    }

    fun removeVar(k: String) {
        io.lanread.app.source.LanJs.java.remove(k)
        refreshVars()
    }

    /** 执行登录页按钮 action：loginUrl 作前置库，java/cache/source 绑定可用 */
    fun runLoginAction(sourceJson: String, action: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val bs = BookSourceParser.parseSingle(sourceJson)
            if (bs == null) {
                actionMessage = "书源解析失败"
                return@launch
            }
            val ret = Analyzer.runJs(action, emptyMap(), bs.loginUrl ?: "", io.lanread.app.source.LanJs.bindings())
            actionMessage = when {
                ret.isNullOrBlank() -> "已执行（脚本无返回值）"
                else -> "返回：$ret"
            }
            refreshVars()
        }
    }

    fun clearActionMessage() { actionMessage = null }

    fun import(text: String) {
        if (text.isBlank()) return
        importing = true
        viewModelScope.launch {
            val r = repo.import(text)
            importMessage = r.summary
            importing = false
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(id, enabled) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteSource(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: SourceViewModel = viewModel(factory = viewModelFactory {
        initializer { SourceViewModel(app) }
    })
    val sources by vm.sources.collectAsState()
    val input = remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SourceEntity?>(null) }
    var varTarget by remember { mutableStateOf<SourceEntity?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            val text = runCatching {
                context.contentResolver.openInputStream(it)?.use { s ->
                    s.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (!text.isNullOrBlank()) vm.import(text)
            else vm.importMessage = "读取文件失败（该文件可能不是 UTF-8 文本）"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "书源",
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
                value = input.value,
                onValueChange = { input.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                placeholder = { Text("粘贴书源 JSON，或输入书源 URL（超长内容可在框内滚动）") },
                minLines = 3
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Button(
                    onClick = { vm.import(input.value.trim()) },
                    enabled = !vm.importing
                ) {
                    Text(if (vm.importing) "导入中..." else "导入")
                }
                Spacer(Modifier.padding(4.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        pickFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }
                ) { Text("选文件") }
                Spacer(Modifier.padding(4.dp))
                Text(
                    "${sources?.size ?: 0} 条",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            vm.importMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    fontSize = 13.sp,
                    color = if (it.startsWith("导入成功")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(8.dp))

            val list = sources
            if (list == null) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { androidx.compose.material3.CircularProgressIndicator() }
            } else if (list.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("暂无书源", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "在上方粘贴 Legado 书源 JSON 或书源 URL 进行导入。\n推荐从可信渠道获取（如社区整理合集），\n书源规则内含 JS 脚本，请只导信得过的来源。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(list, key = { it.id }) { src ->
                        SourceCard(
                            src = src,
                            onToggle = { vm.setEnabled(src.id, it) },
                            onDelete = { deleteTarget = src },
                            onVars = { varTarget = src }
                        )
                    }
                }
            }
        }
    }

    vm.importMessage?.let { }
    deleteTarget?.let { src ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除书源") },
            text = { Text("「${src.name}」将被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(src.id)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    varTarget?.let { src ->
        SourceVarDialog(vm = vm, source = src, onDismiss = { varTarget = null })
    }
}

/**
 * 源变量 / 登录页：渲染 loginUi 控件（按钮执行 loginUrl 定义的 JS 函数），
 * 附带全局变量存的查看 / 增删改（供无 loginUi 的源手动维护）。
 */
@Composable
private fun SourceVarDialog(vm: SourceViewModel, source: SourceEntity, onDismiss: () -> Unit) {
    val bs = remember(source) { BookSourceParser.parseSingle(source.json) }
    val uiItems = remember(bs) { LoginUiParser.parse(bs?.loginUi) }
    val newKey = remember { mutableStateOf("") }
    val newVal = remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(source) { vm.refreshVars() }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 3.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(
                    "源变量 · ${source.name}",
                    fontFamily = LanType.Display,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))

                if (uiItems.isEmpty()) {
                    Text(
                        "该源没有 loginUi 登录页控件。\n可手动维护变量（jsLib 脚本通过 java.get 读取）：",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    // 登录页控件：输入框 + 声明式按钮
                    uiItems.forEach { item ->
                        when (item.type) {
                            "text", "password" -> {
                                val v = remember(item.name) { mutableStateOf(item.value ?: "") }
                                OutlinedTextField(
                                    value = v.value,
                                    onValueChange = { v.value = it },
                                    label = { Text(item.name) },
                                    singleLine = true,
                                    visualTransformation = if (item.type == "password")
                                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                                    else androidx.compose.ui.text.input.VisualTransformation.None,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    trailingIcon = {
                                        TextButton(onClick = {
                                            vm.putVar(item.name, v.value)
                                        }) { Text("保存") }
                                    }
                                )
                            }
                        }
                    }
                    // 无 action 的按钮作为分组标题渲染
                    uiItems.filter { it.type == "button" && it.action.isNullOrBlank() }
                        .forEach { head ->
                            Text(
                                head.name,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    val buttons = uiItems.filter { it.type == "button" && !it.action.isNullOrBlank() }
                    buttons.chunked(2).forEach { rowItems ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { b ->
                                androidx.compose.material3.OutlinedButton(
                                    onClick = {
                                        vm.clearActionMessage()
                                        vm.runLoginAction(source.json, b.action ?: "")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        b.name,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                vm.actionMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "当前变量（${vm.vars.size}）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
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
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
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

                Spacer(Modifier.height(12.dp))
                Text(
                    "提示：变量存于本机（进程内），书源脚本通过 java.get 读取；\n漫蛙源的线路选择、最优线路缓存均存于此。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun SourceCard(
    src: SourceEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onVars: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    src.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (src.group.isBlank()) src.url else "${src.group} · ${src.url}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = src.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onVars) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "源变量",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
