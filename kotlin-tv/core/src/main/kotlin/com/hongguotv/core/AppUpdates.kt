// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

data class AppUpdate(val versionCode: Long, val versionName: String, val minSdk: Int,
                     val tag: String, val fileName: String, val size: Long, val sha256: String, val notes: String) {
    fun newerThan(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk
    val downloadUrl get() = "$RELEASES/download/$tag/$fileName"
    companion object {
        const val REPOSITORY = "WayneShao/hongguoTV"
        const val RELEASES = "https://github.com/$REPOSITORY/releases"
        const val RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        // The RayNeo Full package carries the offline speech model and is about 153 MiB.
        const val MAX_APK_BYTES = 220L * 1024 * 1024
        fun parse(json: String): AppUpdate {
            val o = JSONObject(json)
            require(o.getInt("schema") == 1 && o.getString("packageName") == "com.hongguotv.nativeapp") { "更新信息不匹配" }
            val apk = o.getJSONObject("apk")
            val update = AppUpdate(o.getLong("versionCode"), o.getString("versionName"), o.getInt("minSdk"),
                o.getString("tag"), apk.getString("name"), apk.getLong("size"), apk.getString("sha256").lowercase(), o.optString("notes").take(1500))
            require(update.versionCode in 1..Int.MAX_VALUE.toLong() && update.minSdk in 26..100)
            require(update.versionName.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}")))
            require(update.tag.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")))
            require(update.fileName.matches(Regex("(?:hongguotv-kotlin-[0-9][0-9A-Za-z.+-]{0,80}-android8|hongguoTV-[A-Za-z0-9-]+-[0-9][0-9A-Za-z.+-]{0,80})\\.apk")))
            require(update.size in 1..MAX_APK_BYTES && update.sha256.matches(Regex("[0-9a-f]{64}")))
            return update
        }

        fun parseLatestRelease(json: String, packageName: String, embeddedVoice: Boolean): AppUpdate {
            require(packageName == "com.hongguotv.nativeapp" || packageName == "com.hongguotv.rayneo") { "更新包名不受支持" }
            val release = JSONObject(json)
            val tag = release.getString("tag_name")
            require(tag.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}"))) { "Release 标签不受支持" }
            val assetName = when {
                packageName == "com.hongguotv.nativeapp" -> "hongguoTV-TV-"
                embeddedVoice -> "hongguoTV-X3Pro-Full-"
                else -> "hongguoTV-X3Pro-Keyboard-"
            }
            val variantLabel = when {
                packageName == "com.hongguotv.nativeapp" -> "TV"
                embeddedVoice -> "X3Pro Full"
                else -> "X3Pro Keyboard"
            }
            val asset = (release.optJSONArray("assets") ?: JSONArray()).let { assets ->
                (0 until assets.length()).asSequence().map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").startsWith(assetName) }
                    ?: throw IllegalArgumentException("Release 缺少当前设备版本")
            }
            val fileName = asset.getString("name")
            require(fileName.matches(Regex("hongguoTV-[A-Za-z0-9-]+-[0-9][0-9A-Za-z.+-]{0,80}\\.apk")))
            val line = release.optString("body").lineSequence().firstOrNull {
                it.contains(variantLabel) && it.contains("versionCode")
            } ?: throw IllegalArgumentException("Release 缺少版本号元数据")
            val code = Regex("versionCode\\s*[:=]?\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLong()
                ?: throw IllegalArgumentException("Release 版本号格式不正确")
            val version = Regex("-([0-9][0-9A-Za-z.+-]{0,80})\\.apk$").find(fileName)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("Release 文件名版本不正确")
            val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
            require(digest.matches(Regex("[0-9a-f]{64}"))) { "Release 缺少 SHA-256 摘要" }
            val size = asset.getLong("size")
            require(size in 1..MAX_APK_BYTES)
            return AppUpdate(code, version, 26, tag, fileName, size, digest, release.optString("body").take(1500))
        }
    }
}

/** Streaming validation also protects a cached APK from partial downloads and stale metadata. */
object UpdateFiles {
    fun verify(file: File, update: AppUpdate): Boolean {
        if (!file.isFile || file.length() != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(32768); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
    }
    fun receive(input: InputStream, destination: File, update: AppUpdate, progress: (Int) -> Unit = {}) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768); var total = 0L; var last = -1
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer); if (n < 0) break
                    total += n
                    if (total > update.size) throw IOException("安装包长度异常")
                    output.write(buffer, 0, n)
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != last) { last = percent; progress(percent) }
                }
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包校验未通过，请重新下载")
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { partial.delete() }
    }
}

class AppUpdateRepository(
    private val packageName: String = "com.hongguotv.nativeapp",
    private val embeddedVoice: Boolean = false,
) : java.io.Closeable {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).addNetworkInterceptor { chain ->
            val url = chain.request().url
            // Follow GitHub's CDN redirects, never cleartext or arbitrary manifest-provided hosts.
            if (url.scheme != "https" || url.host !in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
                throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }.build()
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "HongguoTV-Updater")
        .header("Cache-Control", "no-cache").build()
    fun latest(): AppUpdate {
        val call = http.newCall(request(AppUpdate.RELEASE_API))
        call.timeout().timeout(25, TimeUnit.SECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw IOException(if (response.code == 404) "尚无可用的自动更新版本" else "无法检查更新，请稍后重试")
            val bytes = response.body?.byteStream()?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > 65536) throw IOException("更新信息过大"); out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: throw IOException("更新信息为空")
            try { AppUpdate.parseLatestRelease(bytes.toString(Charsets.UTF_8), packageName, embeddedVoice) }
            catch (_: Exception) { throw IOException("GitHub 最新 Release 信息格式不正确") }
        }
    }
    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (UpdateFiles.verify(file, update)) return
        http.newCall(request(update.downloadUrl)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
            val body = response.body ?: throw IOException("安装包为空")
            if (body.contentLength() >= 0 && body.contentLength() != update.size) throw IOException("安装包长度异常")
            body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
        }
    }
    fun cancel() = http.dispatcher.cancelAll()
    override fun close() { cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
