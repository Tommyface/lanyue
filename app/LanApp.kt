package io.lanread.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.lanread.app.source.Http
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 全局崩溃捕获（v0.4.9）：未捕获异常的堆栈落盘到 filesDir/crash/，
 * 设置页「诊断」可查看最近一份，便于真机闪退远程定位。
 */
class LanApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrashLog(this, t, e) }
            previous?.uncaughtException(t, e)
        }
    }

    /**
     * 封面/图片专用加载器（v0.4.11）：Coil 全局替换默认 OkHttp。
     * 书源图床常为 IP 直链 + 自签证书（如漫蛙 52.128.242.34:20608），默认主机名校验必失败；
     * 且部分图床拒绝非浏览器 UA / HTTP/2。图片仅用于显示不执行，故按 Legado 同等策略：
     * HTTP/1.1 + 浏览器 UA + 信任全部证书。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(imageOkHttp())
        .crossfade(true)
        .build()

    private fun imageOkHttp(): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("SSL")
        ssl.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        return OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", Http.UA).build())
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
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
