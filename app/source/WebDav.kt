package io.lanread.app.source

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 客户端（Basic 认证），仅覆盖备份场景所需：
 * PUT 上传备份 / GET 下载备份 / MKCOL 建目录（忽略已存在）/ GET 探测连通与鉴权。
 */
object WebDav {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    const val FILE_NAME = "lanread-backup.json"

    fun fileUrl(server: String): String = server.trim().trimEnd('/') + "/" + FILE_NAME

    private fun auth(user: String, pass: String): String =
        "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)

    /** 探测：null=通过；非 null=错误说明。404 视为通过（可达、鉴权 OK，仅尚无备份文件） */
    fun test(server: String, user: String, pass: String): String? = try {
        val req = Request.Builder().url(fileUrl(server))
            .header("Authorization", auth(user, pass)).get().build()
        client.newCall(req).execute().use { r ->
            when {
                r.code == 401 || r.code == 403 -> "鉴权失败（HTTP ${r.code}）：用户名或密码不正确"
                r.code == 404 -> null
                r.isSuccessful -> null
                else -> "服务器返回 HTTP ${r.code}"
            }
        }
    } catch (e: IllegalArgumentException) {
        "URL 无效：${e.message}"
    } catch (e: Exception) {
        "连接失败：${e.message}"
    }

    /** 上传备份：null=成功；非 null=错误说明。自动尝试建目录（忽略失败）。 */
    fun backup(server: String, user: String, pass: String, json: String): String? {
        val dirUrl = server.trim().trimEnd('/') + "/"
        try {
            val mk = Request.Builder().url(dirUrl)
                .header("Authorization", auth(user, pass))
                .method("MKCOL", null).build()
            client.newCall(mk).execute().use { }
        } catch (ignore: Exception) {
        }
        return try {
            val body = json.toByteArray(Charsets.UTF_8)
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(fileUrl(server))
                .header("Authorization", auth(user, pass))
                .put(body).build()
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) null else "上传失败（HTTP ${r.code}）"
            }
        } catch (e: IllegalArgumentException) {
            "URL 无效：${e.message}"
        } catch (e: Exception) {
            "上传失败：${e.message}"
        }
    }

    /** 下载备份：Pair(text, err)，err=null 且 text 非 null 为成功 */
    fun restore(server: String, user: String, pass: String): Pair<String?, String?> = try {
        val req = Request.Builder().url(fileUrl(server))
            .header("Authorization", auth(user, pass)).get().build()
        client.newCall(req).execute().use { r ->
            when {
                r.code == 401 || r.code == 403 -> Pair(null, "鉴权失败（HTTP ${r.code}）")
                r.code == 404 -> Pair(null, "云端没有备份文件")
                !r.isSuccessful -> Pair(null, "下载失败（HTTP ${r.code}）")
                else -> Pair(r.body?.string() ?: "", null)
            }
        }
    } catch (e: IllegalArgumentException) {
        Pair(null, "URL 无效：${e.message}")
    } catch (e: Exception) {
        Pair(null, "下载失败：${e.message}")
    }
}
