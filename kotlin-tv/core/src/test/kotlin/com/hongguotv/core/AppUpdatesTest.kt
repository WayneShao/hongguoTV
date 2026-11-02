package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class AppUpdatesTest {
    private val bytes = "signed-apk-test-bytes".toByteArray()
    private fun json() = JSONObject().put("schema", 1).put("packageName", "com.hongguotv.nativeapp")
        .put("versionCode", 10001).put("versionName", "0.9.0+1").put("minSdk", 26).put("tag", "kotlin-v0.9.0-build.1")
        .put("apk", JSONObject().put("name", "hongguotv-kotlin-0.9.0-build.1-android8.apk").put("size", bytes.size)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }))
    private fun update() = AppUpdate.parse(json().toString())
    private fun releaseJson() = JSONObject()
        .put("tag_name", "personal-0.11.0")
        .put("body", "- TV：0.11.0-local / versionCode20\n- X3Pro Keyboard：0.11.0-rayneo-keyboard / versionCode26\n- X3Pro Full：0.11.0-rayneo-full / versionCode26")
        .put("assets", org.json.JSONArray()
            .put(JSONObject().put("name", "hongguoTV-TV-0.11.0.apk").put("size", 100).put("digest", "sha256:" + "a".repeat(64)))
            .put(JSONObject().put("name", "hongguoTV-X3Pro-Keyboard-0.11.0.apk").put("size", 200).put("digest", "sha256:" + "b".repeat(64)))
            .put(JSONObject().put("name", "hongguoTV-X3Pro-Full-0.11.0.apk").put("size", 300).put("digest", "sha256:" + "c".repeat(64))))
    private fun withFile(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("update-test").toFile()
        try { block(File(dir, "update.apk")) } finally { dir.deleteRecursively() }
    }
    @Test fun onlyHigherCompatibleVersionsAreUpdates() {
        val update = update()
        assertTrue(update.newerThan(9, 26)); assertFalse(update.newerThan(10001, 36)); assertFalse(update.newerThan(10002, 36)); assertFalse(update.newerThan(9, 25))
    }

    @Test fun latestReleaseSelectsTheRequestedVariantAndUsesPersonalRepository() {
        val tv = AppUpdate.parseLatestRelease(releaseJson().toString(), "com.hongguotv.nativeapp", false)
        assertEquals(20, tv.versionCode)
        assertEquals("0.11.0-local",tv.versionName)
        assertEquals("hongguoTV-TV-0.11.0.apk", tv.fileName)
        assertEquals("https://github.com/WayneShao/hongguoTV/releases/download/personal-0.11.0/hongguoTV-TV-0.11.0.apk", tv.downloadUrl)
        val full = AppUpdate.parseLatestRelease(releaseJson().toString(), "com.hongguotv.rayneo", true)
        assertEquals(26, full.versionCode)
        assertEquals("0.11.0-rayneo-full",full.versionName)
        assertEquals("hongguoTV-X3Pro-Full-0.11.0.apk", full.fileName)
    }

    @Test fun structuredMetadataSelectsAllVariantsAndRejectsIncompleteReleases() {
        val release=releaseJson().put("tag_name","personal-0.12.0")
        val variants=JSONObject()
        for((variant,suffix) in listOf("TV" to "local","X3Pro-Keyboard" to "rayneo-keyboard","X3Pro-Full" to "rayneo-full")) {
            val pkg=if(variant=="TV") "com.hongguotv.nativeapp" else "com.hongguotv.rayneo"
            variants.put(variant,JSONObject().put("packageName",pkg).put("versionName","0.12.0-$suffix").put("versionCode",if(variant=="TV") 21 else 27).put("minSdk",26))
        }
        val assets=release.getJSONArray("assets")
        for(i in 0 until assets.length()) assets.getJSONObject(i).apply {put("name",getString("name").replace("0.11.0","0.12.0"))}
        assets.getJSONObject(2).put("size",153L*1024*1024)
        release.put("body","说明\n<!-- hongguotv-update ${JSONObject().put("schema",1).put("variants",variants)} -->")
        for((pkg,voice) in listOf("com.hongguotv.nativeapp" to false,"com.hongguotv.rayneo" to false,"com.hongguotv.rayneo" to true)) {
            val value=AppUpdate.parseLatestRelease(release.toString(),pkg,voice)
            assertEquals(if(pkg.endsWith("nativeapp"))21L else 27L,value.versionCode)
            assertFalse(value.notes.contains("<!--"))
        }
        assertThrows(IllegalArgumentException::class.java){AppUpdate.parseLatestRelease(release.put("prerelease",true).toString(),"com.hongguotv.rayneo",true)}
        release.put("prerelease",false)
        assets.put(assets.getJSONObject(2))
        assertThrows(IllegalArgumentException::class.java){AppUpdate.parseLatestRelease(release.toString(),"com.hongguotv.rayneo",true)}
    }

    @Test fun liveReleaseSnapshotUsesExactVersionsAndSupportsFullSize() {
        val path=System.getenv("HONGGUO_RELEASE_FIXTURE") ?: return
        val json=File(path).readText()
        for((pkg,voice) in listOf("com.hongguotv.nativeapp" to false,"com.hongguotv.rayneo" to false,"com.hongguotv.rayneo" to true)) {
            val parsed=AppUpdate.parseLatestRelease(json,pkg,voice)
            assertTrue(parsed.versionName.endsWith(if(pkg.endsWith("nativeapp")) "-local" else if(voice) "-rayneo-full" else "-rayneo-keyboard"))
            assertTrue(parsed.sha256.length==64)
        }
    }

    @Test fun repositoryUsesLatestApiAndSeparateMetadataAndDownloadTimeouts() = withFile { destination ->
        val release=releaseJson()
        release.getJSONArray("assets").getJSONObject(2).put("size",bytes.size)
            .put("digest","sha256:"+MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
        val paths=mutableListOf<String>()
        AppUpdateRepository("com.hongguotv.rayneo",true) { builder ->
            builder.addInterceptor { chain ->
                val request=chain.request();paths+=request.url.toString()
                val metadata=request.url.host=="api.github.com"
                assertEquals(if(metadata)25_000_000_000L else 0L,chain.call().timeout().timeoutNanos())
                val value=if(metadata)release.toString().toByteArray() else bytes
                okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(okhttp3.ResponseBody.create(null,value)).build()
            }
        }.use { repository ->
            val selected=repository.latest(); repository.download(selected,destination) {}
            assertTrue(UpdateFiles.verify(destination,selected))
            assertEquals(listOf(AppUpdate.RELEASE_API,selected.downloadUrl),paths)
        }
    }

    @Test fun latestReleaseRejectsMissingOrUntrustedVariantMetadata() {
        val body = releaseJson().put("body", "release without version metadata")
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdate.parseLatestRelease(body.toString(), "com.hongguotv.nativeapp", false)
        }
        val digest = releaseJson().also { it.getJSONArray("assets").getJSONObject(0).put("digest", "md5:bad") }
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdate.parseLatestRelease(digest.toString(), "com.hongguotv.nativeapp", false)
        }
    }
    @Test fun downloadUrlUsesOnlyFixedRepositoryAndValidatedNames() {
        assertEquals("https://github.com/WayneShao/hongguoTV/releases/download/kotlin-v0.9.0-build.1/hongguotv-kotlin-0.9.0-build.1-android8.apk", update().downloadUrl)
        for (value in listOf("../../bad.apk", "evil.apk", "https://example.com/evil.apk", "hongguotv-kotlin-1/2-android8.apk")) {
            val o = json(); o.getJSONObject("apk").put("name", value)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("tag", "../../other").toString()) }
    }
    @Test fun rejectOtherPackageAndInvalidSchema() {
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("packageName", "other.app").toString()) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("schema", 2).toString()) }
    }
    @Test fun enforceVersionSizeAndChecksumBounds() {
        for (code in listOf(0L, -1L, Int.MAX_VALUE.toLong()+1)) assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(json().put("versionCode", code).toString()) }
        for (size in listOf(0L, -1L, AppUpdate.MAX_APK_BYTES+1)) {
            val o = json(); o.getJSONObject("apk").put("size", size)
            assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
        }
        val o = json(); o.getJSONObject("apk").put("sha256", "invalid")
        assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(o.toString()) }
    }
    @Test fun verifiedDownloadAtomicallyReplacesOldFile() = withFile { file ->
        file.writeText("previous"); val progress = mutableListOf<Int>()
        UpdateFiles.receive(ByteArrayInputStream(bytes), file, update(), progress::add)
        assertTrue(UpdateFiles.verify(file, update())); assertEquals(100, progress.last()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun truncatedDownloadPreservesPreviousFile() = withFile { file ->
        file.writeText("previous")
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes.copyOf(4)), file, update()) }
        assertEquals("previous", file.readText()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun oversizedDownloadIsRejectedAndRemoved() = withFile { file ->
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes+byteArrayOf(1)), file, update()) }
        assertFalse(file.exists()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun checksumMismatchCannotBecomeInstallableFile() = withFile { file ->
        val corrupt = bytes.copyOf(); corrupt[0] = 0
        assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(corrupt), file, update()) }
        assertFalse(file.exists()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun interruptedDownloadCleansUpPartialFile() = withFile { file ->
        Thread.currentThread().interrupt()
        try { assertThrows(IOException::class.java) { UpdateFiles.receive(ByteArrayInputStream(bytes), file, update()) } }
        finally { Thread.interrupted() }
        assertFalse(file.exists()); assertFalse(File(file.path+".part").exists())
    }
    @Test fun cacheMustStillMatchExpectedContent() = withFile { file ->
        file.writeBytes(bytes); assertTrue(UpdateFiles.verify(file, update()))
        file.writeBytes(ByteArray(bytes.size)); assertFalse(UpdateFiles.verify(file, update()))
    }
}
