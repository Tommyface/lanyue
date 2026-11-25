package io.lanread.app.source

import io.lanread.app.data.AppDatabase
import io.lanread.app.data.BookEntity
import io.lanread.app.data.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupData(
    val app: String = "LanRead",
    val schema: Int = 1,
    val exportedAt: Long,
    val books: List<BookEntity>,
    val sources: List<SourceEntity>
)

/** 书架 / 书源 / 进度的 JSON 备份导出与恢复（覆盖式） */
class BackupRepository(private val db: AppDatabase) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    suspend fun exportText(): String = withContext(Dispatchers.IO) {
        val data = BackupData(
            exportedAt = System.currentTimeMillis(),
            books = db.bookDao().allBooks(),
            sources = db.sourceDao().allSources()
        )
        json.encodeToString(BackupData.serializer(), data)
    }

    /** 恢复（覆盖式）：返回 (书架条数, 书源条数)；解析失败抛异常 */
    suspend fun importText(text: String): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val data = json.decodeFromString(BackupData.serializer(), text)
        if (data.app != "LanRead") {
            throw IllegalArgumentException("不是澜阅的备份文件（app=${data.app}）")
        }
        db.bookDao().clear()
        db.sourceDao().clear()
        db.bookDao().insertAll(data.books.map { it.copy(id = 0) })
        db.sourceDao().insertAll(data.sources.map { it.copy(id = 0) })
        data.books.size to data.sources.size
    }
}
