package io.lanread.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val type: Int = 0,              // 0 = local txt, 1 = online
    val uri: String = "",           // local SAF uri or online book url
    val sourceId: Long = 0,
    val sourceName: String = "",
    val coverUrl: String = "",
    val intro: String = "",
    val latestChapter: String = "",
    val chapterIndex: Int = 0,
    val page: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0
)

@Serializable
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val group: String = "",
    val enabled: Boolean = true,
    val json: String
)

/** 订阅源（Legado RssSource） */
@Serializable
@Entity(tableName = "rss_sources")
data class RssSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val group: String = "",
    val enabled: Boolean = true,
    val json: String
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, id DESC")
    fun all(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun byId(id: Long): BookEntity?

    /** 书架去重：同源 + 同内容 URL 视为同一本书 */
    @Query("SELECT * FROM books WHERE sourceId = :sourceId AND uri = :uri LIMIT 1")
    suspend fun bySourceAndUri(sourceId: Long, uri: String): BookEntity?

    @Query("SELECT * FROM books")
    suspend fun allBooks(): List<BookEntity>

    @Query("DELETE FROM books")
    suspend fun clear()

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Insert
    suspend fun insertAll(books: List<BookEntity>)

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY name")
    fun all(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun byId(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE enabled = 1")
    suspend fun enabled(): List<SourceEntity>

    @Query("SELECT * FROM sources")
    suspend fun allSources(): List<SourceEntity>

    @Query("DELETE FROM sources")
    suspend fun clear()

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Insert
    suspend fun insertAll(list: List<SourceEntity>)

    @Query("UPDATE sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM sources WHERE url = :url")
    suspend fun countByUrl(url: String): Int
}

@Dao
interface RssSourceDao {
    @Query("SELECT * FROM rss_sources ORDER BY name")
    fun all(): Flow<List<RssSourceEntity>>

    @Query("SELECT * FROM rss_sources WHERE id = :id")
    suspend fun byId(id: Long): RssSourceEntity?

    @Query("SELECT * FROM rss_sources")
    suspend fun allSources(): List<RssSourceEntity>

    @Insert
    suspend fun insert(source: RssSourceEntity): Long

    @Query("UPDATE rss_sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM rss_sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM rss_sources WHERE url = :url")
    suspend fun countByUrl(url: String): Int
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `rss_sources` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`group` TEXT NOT NULL, " +
                "`enabled` INTEGER NOT NULL, " +
                "`json` TEXT NOT NULL)"
        )
    }
}

@Database(
    entities = [BookEntity::class, SourceEntity::class, RssSourceEntity::class],
    version = 2
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun sourceDao(): SourceDao
    abstract fun rssSourceDao(): RssSourceDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lanread.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
