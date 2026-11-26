package io.lanread.app

import android.app.Application
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 全局崩溃捕获（v0.4.9）：未捕获异常的堆栈落盘到 filesDir/crash/，
 * 设置页「诊断」可查看最近一份，便于真机闪退远程定位。
 */
class LanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrashLog(this, t, e) }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        fun crashDir(ctx: Context): File =
            File(ctx.filesDir, "crash").apply { mkdirs() }

        /** 最近一次崩溃日志内容（无则 null） */
        fun latestCrashLog(ctx: Context): String? {
            val files = crashDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
                ?: return null
            val newest = files.maxByOrNull { it.lastModified() } ?: return null
            return runCatching { newest.readText() }.getOrNull()
        }

        private fun writeCrashLog(ctx: Context, thread: Thread, e: Throwable) {
            val ver = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull() ?: "?"
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
            val f = File(crashDir(ctx), "crash-$stamp.txt")
            // 只留最近 5 份，防长期膨胀
            f.writeText(
                buildString {
                    append("time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()))
                    append("\nversion: ").append(ver)
                    append("\nthread: ").append(thread.name)
                    append("\n\n")
                    append(android.util.Log.getStackTraceString(e))
                }
            )
            val old = crashDir(ctx).listFiles { g -> g.isFile && g.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() } ?: return
            old.drop(5).forEach { runCatching { it.delete() } }
        }
    }
}
