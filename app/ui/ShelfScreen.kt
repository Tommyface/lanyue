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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import io.lanread.app.R
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
                // v0.4.23：空态插画（开卷小书浮于澜水）
                Icon(
                    painterResource(R.drawable.ic_shelf_empty),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(96.dp)
                )
                Spacer(Modifier.height(16.dp))
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
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.id }) { book ->
                    BookRowCard(book = book, onClick = {
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
 * 书架条目（v0.4.13，按参考图重设计；v0.4.23 视觉重绘）：单列列表——左侧 3:4 圆角封面（无封面回退书名首字底纹），
 * 右侧书名/作者/进度/最新章节/两行简介。长按移出书架。
 * 签名元素：条目左缘 4dp 书脊色带（按书 id 囷定四色系之一——每本书有自己的书脊）。
 */
@Composable
private fun BookRowCard(book: BookEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
            }
            .padding(start = 0.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 书脊色带：左缘 4dp 竖色带（v0.4.23 签名元素）
        Box(
            Modifier
                .width(4.dp)
                .height(88.dp)
                .background(SpinePalette.of(book.id))
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .width(66.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (book.coverUrl.isNotBlank()) {
                coil.compose.AsyncImage(
                    model = book.coverUrl,
                    contentDescription = "封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    book.title.take(1),
                    fontFamily = LanType.Display,
                    fontSize = 26.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                fontFamily = LanType.Display,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "作者：${book.author.ifBlank { if (book.type == 0) "本地导入" else "佚名" }}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "已读至第 ${book.chapterIndex + 1} 章",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (book.latestChapter.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "最新：${book.latestChapter}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (book.intro.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    book.intro,
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
