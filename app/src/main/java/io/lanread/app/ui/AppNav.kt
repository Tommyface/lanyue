package io.lanread.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val SHELF = "shelf"
    const val SEARCH = "search"
    const val SOURCES = "sources"
    const val SETTINGS = "settings"
    const val RSS = "rss"
    const val RSS_ARTICLE = "rssArticle/{index}"
    const val EXPLORE = "explore"
    const val BOOK = "book/{bookId}"
    const val PREVIEW = "preview/{hit}"
    const val READER = "reader/{bookId}?chapter={chapter}&url={url}"
    const val REPO = "repo"

    fun book(bookId: Long) = "book/$bookId"
    fun preview(hit: io.lanread.app.source.SourceRepository.SearchHit): String =
        "preview/" + android.net.Uri.encode(
            io.lanread.app.source.BookSourceParser.json.encodeToString(
                io.lanread.app.source.SourceRepository.SearchHit.serializer(), hit
            )
        )
    fun reader(bookId: Long, chapter: Int, url: String) =
        "reader/$bookId?chapter=$chapter&url=${android.net.Uri.encode(url)}"
}

@Composable
fun AppNav() {
    val nav: NavHostController = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SHELF) {
        composable(Routes.SHELF) { ShelfScreen(nav) }
        composable(Routes.SEARCH) { SearchScreen(nav) }
        composable(Routes.SOURCES) { SourceScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.RSS) { RssScreen(nav) }
        composable(Routes.EXPLORE) { ExploreScreen(nav) }
        composable(
            Routes.RSS_ARTICLE,
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { entry ->
            RssArticleScreen(nav, entry.arguments?.getInt("index") ?: 0)
        }
        composable(
            Routes.BOOK,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { entry ->
            BookScreen(nav, entry.arguments?.getLong("bookId") ?: 0L)
        }
        composable(
            Routes.PREVIEW,
            arguments = listOf(navArgument("hit") { type = NavType.StringType })
        ) { entry ->
            val hit = runCatching {
                io.lanread.app.source.BookSourceParser.json.decodeFromString(
                    io.lanread.app.source.SourceRepository.SearchHit.serializer(),
                    entry.arguments?.getString("hit") ?: ""
                )
            }.getOrNull()
            BookScreen(nav, 0L, hit)
        }
        composable(Routes.REPO) { RepoScreen(nav) }
        composable(
            Routes.READER,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("chapter") { type = NavType.IntType; defaultValue = 0 },
                navArgument("url") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            ReaderScreen(
                nav = nav,
                bookId = entry.arguments?.getLong("bookId") ?: 0L,
                chapterArg = entry.arguments?.getInt("chapter") ?: 0,
                urlArg = entry.arguments?.getString("url") ?: ""
            )
        }
    }
}
