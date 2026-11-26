package io.lanread.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import io.lanread.app.data.AppDatabase
import io.lanread.app.data.BookEntity
import io.lanread.app.reader.TxtCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShelfViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val bookDao = AppDatabase.get(app).bookDao()

    val books = bookDao.all()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun importTxt(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<android.app.Application>()
                        .contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@withContext
                    val text = TxtCodec.decode(bytes)
                    val name = uri.lastPathSegment?.substringBeforeLast('.') ?: "未命名"
                    bookDao.insert(
                        BookEntity(
                            title = name,
                            author = "本地导入",
                            type = 0,
                            uri = uri.toString(),
                            intro = "共 ${text.length} 字"
                        )
                    )
                }
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { bookDao.delete(id) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: ShelfViewModel = viewModel(factory = viewModelFactory {
        initializer { ShelfViewModel(app) }
    })
    val books by vm.books.collectAsState()

    var deleteTarget by remember { mutableStateOf<BookEntity?>(null) }

    val pickTxt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.importTxt(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "澜阅",
                        fontFamily = LanType.Display,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp
                    )
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.SEARCH) }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = { nav.navigate(Routes.EXPLORE) }) {
                        Icon(Icons.Filled.Menu, contentDescription = "发现")
                    }
                    IconButton(onClick = { nav.navigate(Routes.RSS) }) {
                        Icon(Icons.Filled.Notifications, contentDescription = "订阅")
                    }
                    IconButton(onClick = { nav.navigate(Routes.SOURCES) }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "书源")
                    }
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { pickTxt.launch(arrayOf("text/plain", "application/octet-stream")) }) {
                Icon(Icons.Filled.Add, contentDescription = "导入本地 TXT")
            }
        }
    ) { padding ->
        val list = books
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (list.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "书架还空着",
                    fontFamily = LanType.Display,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "点右下角 + 导入一本本地 TXT；\n或从书源找书，加入书架。\n首次使用，先到「书源」导入 Legado 书源。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 64.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.id }) { book ->
                    SpineCard(book = book, onClick = {
                        if (book.type == 0) {
                            nav.navigate(Routes.reader(book.id, book.chapterIndex, book.uri))
                        } else {
                            nav.navigate(Routes.book(book.id))
                        }
                    }, onLongClick = { deleteTarget = book })
                }
            }
        }
    }

    deleteTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("移出书架") },
            text = { Text("《${book.title}》将连同阅读进度一起移出书架。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(book.id)
                    deleteTarget = null
                }) { Text("移出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("留下") }
            }
        )
    }
}

/**
 * 签名元素：书脊式竖排书卡。
 * 中文标题按单字竖排（书脊形制）；无汉字的标题回退为横排。
 * 底端色带标注书的来源：竹青=本地，澜水=在线。
 */
@Composable
private fun SpineCard(book: BookEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    val band = when (book.type) {
        0 -> MaterialTheme.colorScheme.tertiary   // 本地：竹青
        2 -> MaterialTheme.colorScheme.primary    // 漫画：墨青
        else -> MaterialTheme.colorScheme.secondary // 在线：澜水
    }
    Box(
        Modifier
            .aspectRatio(0.34f)
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
            }
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SpineTitle(book.title)
            Spacer(Modifier.weight(1f))
            Text(
                book.author.ifBlank { if (book.type == 0) "本地" else book.sourceName },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 底端色带：来源标记（竹=本地，澜=在线）
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.86f)
                .height(5.dp)
                .background(band)
        )
    }
}

@Composable
private fun SpineTitle(title: String, maxChars: Int = 9) {
    val hasCjk = title.any { it.code in 0x4E00..0x9FFF }
    if (hasCjk) {
        val shown = if (title.length > maxChars) title.take(maxChars - 1) + "…" else title
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            for (c in shown) {
                Text(
                    c.toString(),
                    fontFamily = LanType.Display,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    } else {
        Text(
            title,
            fontFamily = LanType.Display,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
