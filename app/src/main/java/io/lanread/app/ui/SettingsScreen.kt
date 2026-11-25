package io.lanread.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import io.lanread.app.data.AppDatabase
import io.lanread.app.data.Prefs
import io.lanread.app.source.BackupRepository
import io.lanread.app.source.WebDav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val backup = BackupRepository(AppDatabase.get(app))

    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    fun saveDav(url: String, user: String, pass: String) {
        val a = getApplication<android.app.Application>()
        Prefs.setWebdavUrl(a, url.trim())
        Prefs.setWebdavUser(a, user.trim())
        Prefs.setWebdavPass(a, pass)
        message = "WebDAV 配置已保存（本地明文存储，仅保存在应用私有目录）"
    }

    fun testDav(url: String, user: String, pass: String) = launchIo {
        message = WebDav.test(url, user, pass)
            ?: "连接与鉴权通过（404 = 还没有备份文件，属正常）"
    }

    fun backupDav(url: String, user: String, pass: String) = launchIo {
        val json = backup.exportText()
        message = WebDav.backup(url, user, pass, json)
            ?: "已备份到云端（约 ${json.length / 1024} KB，文件 ${WebDav.FILE_NAME}）"
    }

    fun restoreDav(url: String, user: String, pass: String) = launchIo {
        val (text, err) = WebDav.restore(url, user, pass)
        if (err != null) {
            message = err
        } else {
            val (books, sources) = backup.importText(text ?: "")
            message = "已从云端恢复：书架 $books 本、书源 $sources 条（覆盖原数据）"
        }
    }

    suspend fun exportText(): String = backup.exportText()

    fun importText(text: String) = launchIo {
        val (books, sources) = backup.importText(text)
        message = "导入成功：书架 $books 本、书源 $sources 条（覆盖原数据）"
    }

    private fun launchIo(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                message = "失败：${e.message}"
            }
            busy = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory {
        initializer { SettingsViewModel(app) }
    })
    val scope = rememberCoroutineScope()

    val url = remember { mutableStateOf(Prefs.webdavUrl(context)) }
    val user = remember { mutableStateOf(Prefs.webdavUser(context)) }
    val pass = remember { mutableStateOf(Prefs.webdavPass(context)) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { u ->
            scope.launch {
                try {
                    val json = vm.exportText()
                    context.contentResolver.openOutputStream(u)
                        ?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    vm.message = "已导出 JSON 备份文件"
                } catch (e: Exception) {
                    vm.message = "导出失败：${e.message}"
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { u ->
            val text = runCatching {
                context.contentResolver.openInputStream(u)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                vm.message = "读取文件失败（需为 UTF-8 的 JSON 文本）"
            } else {
                vm.importText(text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
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
                .verticalScroll(rememberScrollState())
        ) {
            Text("WebDAV 同步", fontSize = 16.sp, fontFamily = LanType.Display, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "填写服务器上已存在的目录 URL（如 https://dav.example.com/lanread/），备份文件为 ${WebDav.FILE_NAME}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = url.value,
                onValueChange = { url.value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("服务器目录 URL") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = user.value,
                onValueChange = { user.value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("用户名") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pass.value,
                onValueChange = { pass.value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("密码 / 应用密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { vm.saveDav(url.value, user.value, pass.value) }) {
                    Text("保存配置")
                }
                OutlinedButton(
                    onClick = { vm.testDav(url.value, user.value, pass.value) },
                    enabled = !vm.busy
                ) { Text("测试连接") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.backupDav(url.value, user.value, pass.value) },
                    enabled = !vm.busy
                ) { Text(if (vm.busy) "处理中…" else "备份到云端") }
                OutlinedButton(
                    onClick = { vm.restoreDav(url.value, user.value, pass.value) },
                    enabled = !vm.busy
                ) { Text("从云端恢复") }
            }

            Spacer(Modifier.height(20.dp))
            Text("本地备份", fontSize = 16.sp, fontFamily = LanType.Display, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "导出书架、书源、阅读进度为 JSON 文件；导入为覆盖式（清空后写入）。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("lanread-backup.json") }) {
                    Text("导出 JSON 文件")
                }
                OutlinedButton(onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }) { Text("导入 JSON 文件") }
            }

            Spacer(Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("说明", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "· 恢复/导入均为覆盖式操作，会清空当前书架与书源后写入\n· WebDAV 密码以明文保存在应用私有目录（data/data/io.lanread.app），不外传任何其他服务器\n· 备份不含本地 TXT 文件本体，重新导入需保留原 TXT 文件",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            vm.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    fontSize = 13.sp,
                    color = if (it.contains("失败") || it.contains("无效") || it.contains("错误"))
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
