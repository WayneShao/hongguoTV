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
            require(packageName in setOf("com.hongguotv.nativeapp", "com.hongguotv.rayneo"))
            val release=JSONObject(json)
            require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "只接受正式发布版本" }
            val tag=release.getString("tag_name")
            require(tag.matches(Regex("personal-[0-9]+\\.[0-9]+\\.[0-9]+")))
            val number=tag.removePrefix("personal-")
            val variant=when {packageName=="com.hongguotv.nativeapp" -> "TV"; embeddedVoice -> "X3Pro-Full"; else -> "X3Pro-Keyboard"}
            val suffix=when(variant){"TV" -> "local"; "X3Pro-Full" -> "rayneo-full"; else -> "rayneo-keyboard"}
            val fileName="hongguoTV-$variant-$number.apk"
            val assets=release.getJSONArray("assets").objects().filter {it.optString("name")==fileName}
            require(assets.size==1) { "Release 缺少当前版本安装包或出现重复资产" }
            val asset=assets.single()
            require(asset.optString("state","uploaded")=="uploaded")
            val body=release.optString("body")
            val marker="<!-- hongguotv-update "
            val start=body.indexOf(marker)
            val code: Long
            val minSdk: Int
            val versionName="$number-$suffix"
            if(start>=0) {
                val end=body.indexOf(" -->",start+marker.length)
                require(end>start && body.indexOf(marker,start+marker.length)<0)
                val meta=JSONObject(body.substring(start+marker.length,end))
                require(meta.getInt("schema")==1)
                val entry=meta.getJSONObject("variants").getJSONObject(variant)
                require(entry.getString("packageName")==packageName && entry.getString("versionName")==versionName)
                code=entry.getLong("versionCode");minSdk=entry.getInt("minSdk")
            } else {
                // Compatibility with personal-0.11.0, before structured release metadata.
                require(tag=="personal-0.11.0") { "Release 缺少更新元数据" }
                val label=variant.replace('-',' ')
                val line=body.lineSequence().map {it.replace("**", "").trim()}
                    .singleOrNull {it.startsWith("- $label：") || it.startsWith("- $label:")}
                    ?: throw IllegalArgumentException("Release 缺少版本号元数据")
                require(line.contains(versionName))
                code=Regex("versionCode\\s*[:=]?\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLong()
                    ?: throw IllegalArgumentException("Release 版本号格式不正确")
                minSdk=26
            }
            require(code in 1..Int.MAX_VALUE.toLong() && minSdk in 26..100)
            val digest=asset.getString("digest")
            require(digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) { "Release 缺少 SHA-256 摘要" }
            val size=asset.getLong("size");require(size in 1..MAX_APK_BYTES)
            val update=AppUpdate(code,versionName,minSdk,tag,fileName,size,digest.substring(7).lowercase(),body.substringBefore(marker).take(1500))
            require(!asset.has("browser_download_url") || asset.getString("browser_download_url")==update.downloadUrl)
            return update
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

/** 默认直连 GitHub；仅网络失败时按所选线路回退到已验证的镜像。 */
class AppUpdateRepository(
    private val packageName: String = "com.hongguotv.nativeapp",
    private val embeddedVoice: Boolean = false,
    private val route: () -> String = { "auto" },
    configure: (OkHttpClient.Builder) -> Unit = {},
) : java.io.Closeable {
    private val permitted = permittedHosts()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS).addNetworkInterceptor { chain ->
            val url = chain.request().url
            // 只跟随 GitHub 及其 CDN 的 HTTPS 跳转，绝不允许明文或清单提供的任意主机。
            if (url.scheme != "https" || url.host !in permitted)
                throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }.apply(configure).build()
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "HongguoTV-Updater")
        .header("Cache-Control", "no-cache").build()
    fun latest(): AppUpdate {
        var failure: IOException? = null
        for (url in routed(AppUpdate.RELEASE_API, route())) {
            if (Thread.currentThread().isInterrupted) throw IOException("检查更新已取消")
            try {
                return release(url)
            } catch (e: NoReleaseException) {
                // 直连明确表示没有正式版本时，镜像不会改变结论，也不再拖延等待。
                if (url == AppUpdate.RELEASE_API) throw e
                failure = e
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("无法检查更新，请稍后重试")
    }
    private fun release(url: String): AppUpdate {
        val call = http.newCall(request(url))
        call.timeout().timeout(25, TimeUnit.SECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful) throw when (response.code) {
                404 -> NoReleaseException()
                else -> IOException("无法检查更新，请稍后重试")
            }
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
        var failure: IOException? = null
        for (url in routed(update.downloadUrl, route())) {
            if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
            try { fetch(url, update, file, progress); return }
            catch (e: IOException) {
                // 用户取消后不得继续尝试镜像，否则会拖慢退出并误报失败原因。
                if (Thread.currentThread().isInterrupted) throw e
                failure = e
            }
        }
        throw failure ?: IOException("安装包下载失败，请稍后重试")
    }
    private fun fetch(url: String, update: AppUpdate, file: File, progress: (Int) -> Unit) {
        http.newCall(request(url)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
            val body = response.body ?: throw IOException("安装包为空")
            if (body.contentLength() >= 0 && body.contentLength() != update.size) throw IOException("安装包长度异常")
            body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
        }
    }
    fun cancel() = http.dispatcher.cancelAll()
    override fun close() { cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }

    companion object {
        /** 只开放 2026-11-09 复测可用的镜像转发；其余站点未通过元数据与 APK 双重验证。 */
        val DEFAULT_ACCELERATORS = listOf("https://gh-proxy.com/")
        private val GITHUB_HOSTS = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        /** 仅接受根路径的 HTTPS 前缀，防止把下载引向任意路径或明文主机。 */
        fun hostOf(prefix: String): String? = try {
            val uri = java.net.URI(prefix)
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.port !in setOf(-1, 443)) null
            else uri.host?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
        /** 直连优先，随后每个可用镜像各试一次。 */
        fun accelerated(url: String, accelerators: List<String> = DEFAULT_ACCELERATORS): List<String> =
            listOf(url) + accelerators.mapNotNull { prefix -> hostOf(prefix)?.let { prefix.trimEnd('/') + "/" + url } }
        /** 显式镜像只走镜像，不等待直连，也不会静默换线。 */
        fun routed(url: String, route: String, accelerators: List<String> = DEFAULT_ACCELERATORS): List<String> = when (route) {
            "direct" -> listOf(url)
            in accelerators -> listOf(route.trimEnd('/') + "/" + url)
            else -> accelerated(url, accelerators)
        }
        fun permittedHosts(accelerators: List<String> = DEFAULT_ACCELERATORS): Set<String> =
            GITHUB_HOSTS + accelerators.mapNotNull(::hostOf)
    }
}

private class NoReleaseException : IOException("尚无可用的自动更新版本")
