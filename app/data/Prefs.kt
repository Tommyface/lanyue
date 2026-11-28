package io.lanread.app.data

import android.content.Context

object Prefs {
    private const val NAME = "lanread"

    fun fontSize(context: Context): Int =
        prefs(context).getInt("fontSize", 18)

    fun setFontSize(context: Context, v: Int) {
        prefs(context).edit().putInt("fontSize", v).apply()
    }

    fun lineSpacing(context: Context): Float =
        prefs(context).getFloat("lineSpacing", 1.6f)

    fun setLineSpacing(context: Context, v: Float) {
        prefs(context).edit().putFloat("lineSpacing", v).apply()
    }

    fun readerTheme(context: Context): Int =
        prefs(context).getInt("readerTheme", 0)

    fun setReaderTheme(context: Context, v: Int) {
        prefs(context).edit().putInt("readerTheme", v).apply()
    }

    // 0=左右翻页（分页+点击翻页） 1=上下滚动（全文字连续滚动）
    fun pageMode(context: Context): Int =
        prefs(context).getInt("pageMode", 0)

    fun setPageMode(context: Context, v: Int) {
        prefs(context).edit().putInt("pageMode", v).apply()
    }

    // 订阅源默认显示源 ID（0=未设置，自动选第一个）
    fun rssDefaultSourceId(context: Context): Long =
        prefs(context).getLong("rssDefaultSourceId", 0L)

    fun setRssDefaultSourceId(context: Context, v: Long) {
        prefs(context).edit().putLong("rssDefaultSourceId", v).apply()
    }

    // ---------- WebDAV（明文存于应用私有目录，见 README 安全说明） ----------
    fun webdavUrl(context: Context): String =
        prefs(context).getString("webdavUrl", "") ?: ""

    fun setWebdavUrl(context: Context, v: String) {
        prefs(context).edit().putString("webdavUrl", v).apply()
    }

    fun webdavUser(context: Context): String =
        prefs(context).getString("webdavUser", "") ?: ""

    fun setWebdavUser(context: Context, v: String) {
        prefs(context).edit().putString("webdavUser", v).apply()
    }

    fun webdavPass(context: Context): String =
        prefs(context).getString("webdavPass", "") ?: ""

    fun setWebdavPass(context: Context, v: String) {
        prefs(context).edit().putString("webdavPass", v).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
