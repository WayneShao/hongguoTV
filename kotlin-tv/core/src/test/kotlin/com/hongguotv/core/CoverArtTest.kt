// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account endpoints return signed HEIC artwork from fqnovelpic; Android 7/8 TVs cannot
 * decode HEIF. The same object key is available unsigned as JPEG from byteimg. Only the
 * exact known CDN shape may be rewritten, and the loader must be able to recover rows
 * that were persisted by older builds.
 */
class CoverArtTest {
    private val heic =
        "https://p3-reading-sign.fqnovelpic.com/novel-pic/033873814a4b74d4365620010d16548e~tplv-81nmtwyey9-superreso-ai~:400:0.heic?lk3s=64477e16&x-expires=1797161568&x-signature=q6g0XatofVaCl7TP4mGra8u5KNc%3D"
    private val expected =
        "https://p3-novel.byteimg.com/novel-pic/033873814a4b74d4365620010d16548e~tplv-shrink:640:0.image"

    @Test fun signedHeicCoverIsRewrittenToUnsignedByteimgJpeg() {
        assertEquals(expected, CoverArt.forDisplay(heic))
        assertEquals(expected, CoverArt.fallbackUrl(heic))
    }

    @Test fun escapedUrlVariantsAreCleanedBeforeRewriting() {
        assertEquals(expected, CoverArt.forDisplay(heic.replace("/", "\\u002F")))
        assertEquals(expected, CoverArt.forDisplay(heic.replace("/", "\\/")))
    }

    @Test fun unrelatedArtworkAndEmptyValuesPassThrough() {
        assertEquals("", CoverArt.forDisplay(""))
        assertEquals("", CoverArt.forDisplay("   "))
        val webp =
            "https://p6-novel.byteimg.com/novel-pic/973dad4339e00fa9a5d1bf71e6431245~tplv-shrink:640:0.webp"
        assertEquals(webp, CoverArt.forDisplay(webp))
        assertEquals(webp, CoverArt.forDisplay("  $webp  "))
        assertEquals("https://example.com/a.jpg", CoverArt.forDisplay("https://example.com/a.jpg"))
        assertEquals("http://p3-reading-sign.fqnovelpic.com/novel-pic/$hash.heic",
            CoverArt.forDisplay("http://p3-reading-sign.fqnovelpic.com/novel-pic/$hash.heic"))
        assertNull(CoverArt.fallbackUrl("https://example.com/a.jpg"))
        assertNull(CoverArt.fallbackUrl(webp))
    }

    @Test fun lookalikeHostsAndUserInfoCannotBeRewritten() {
        for (raw in listOf(
            "https://evilfqnovelpic.com/novel-pic/$hash.heic",
            "https://fqnovelpic.com.evil.example/novel-pic/$hash.heic",
            "https://evil.example/fqnovelpic.com/novel-pic/$hash.heic",
            "https://fqnovelpic.com@evil.example/novel-pic/$hash.heic",
            "https://fqnovelpic.com:8443/novel-pic/$hash.heic",
            "https://fqnovelpic.com:443@evil.example/novel-pic/$hash.heic",
        )) {
            assertEquals(cleanUrl(raw), CoverArt.forDisplay(raw))
            assertNull(CoverArt.fallbackUrl(raw))
        }
    }

    @Test fun malformedPathsAndHashesAreLeftAlone() {
        for (raw in listOf(
            "https://p3-reading-sign.fqnovelpic.com/novel-pic/$hash/extra.heic",
            "https://p3-reading-sign.fqnovelpic.com/prefix/novel-pic/$hash.heic",
            "https://p3-reading-sign.fqnovelpic.com/novel-pic/not-a-hash.heic",
            "https://p3-reading-sign.fqnovelpic.com/novel-pic/${"a".repeat(15)}.heic",
            "https://p3-reading-sign.fqnovelpic.com/novel-pic/${"a".repeat(65)}.heic",
            "https://p3-reading-sign.fqnovelpic.com/novel-pic/.heic",
        )) {
            assertEquals(cleanUrl(raw), CoverArt.forDisplay(raw))
            assertNull(CoverArt.fallbackUrl(raw))
        }
    }

    @Test fun uppercaseHexAndBareObjectKeysAreRecognized() {
        val upper = "A".repeat(32)
        assertEquals(
            "https://p3-novel.byteimg.com/novel-pic/$upper~tplv-shrink:640:0.image",
            CoverArt.forDisplay("https://p3-reading-sign.fqnovelpic.com/novel-pic/$upper~tplv-x:400:0.heic"),
        )
        assertEquals(expected, CoverArt.forDisplay(
            "https://p3-reading-sign.fqnovelpic.com/novel-pic/033873814a4b74d4365620010d16548e",
        ))
        assertEquals(expected, CoverArt.forDisplay(
            "https://p3-reading-sign.fqnovelpic.com/novel-pic/033873814a4b74d4365620010d16548e~tplv-shrink:640:0.image?x-signature=old",
        ))
    }

    @Test fun fallbackTargetIsNeverUsedAsAnotherFallback() {
        assertNull(CoverArt.fallbackUrl(expected))
    }

    @Test fun favoritesRewriteCoversEndToEnd() {
        val repo = accountRepository { request ->
            response(request, """{"code":0,"data":{"video_shelf_info":[{
                "book_id_str":"333","book_name":"收藏剧","thumb_url":"$heic","episode_cnt":8
            }]}}""")
        }
        val cover = repo.accountFavorites().items.single().cover
        assertEquals(expected, cover)
        assertTrue(!cover.contains("fqnovelpic"))
        assertTrue(!cover.contains("x-signature"))
    }

    @Test fun historyRewriteCoversEndToEnd() {
        val repo = accountRepository { request ->
            if (request.url.queryParameter("book_type") == "2") response(request, """{"code":0,"data":{"data_list":[{
                "book_id_str":"111","book_name":"短剧一","thumb_url":"$heic","episode_cnt":12
            }]}}""") else response(request, """{"code":0,"data":{"data_list":[]}}""")
        }
        assertEquals(expected, repo.accountHistory().items.single().cover)
    }

    @Test fun reservationsRewriteCoversEndToEnd() {
        val repo = accountRepository { request ->
            if (request.url.queryParameter("is_online") == "false") response(request, """{"code":0,"data":{"subscribe_items":[{
                "item_id":"444","item_type":14,"name":"待播剧","cover":"$heic","is_online":false
            }]}}""") else response(request, """{"code":0,"data":{"subscribe_items":[]}}""")
        }
        assertEquals(expected, repo.accountReservations().items.single().cover)
    }

    @Test fun homeAndSearchRewriteCoversWhileKeepingUnrelatedArtwork() {
        val home = repository { """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[
            {"series_id":"456","series_name":"首页","series_cover":"$heic"},
            {"series_id":"457","series_name":"普通","series_cover":"https://example.com/a.jpg"}],
            "pagination":{"totalPages":1}}}};</script>""" }.home(1, ContentType.SHORT)
        assertEquals(expected, home.items[0].cover)
        assertEquals("https://example.com/a.jpg", home.items[1].cover)

        val search = repository { request ->
            if (request.url.host.contains("fqnovel.com")) {
                JSONObject().put("code", 0).put("search_tabs", JSONArray().put(JSONObject()
                    .put("tab_type", 11).put("has_more", false)
                    .put("data", JSONArray().put(JSONObject().put("video_data", JSONArray().put(JSONObject()
                        .put("series_id", "999").put("title", "搜索剧").put("cover", heic))))))).toString()
            } else error("unexpected host")
        }.search("修仙", 1)
        assertEquals(expected, search.items.single().cover)
    }

    @Test fun comicRankJsonAndRenderedCoversAreRewritten() {
        val payload = JSONObject().put("isSuccess", true)
            .put("rankList", JSONArray().put(JSONObject().put("seriesId", "123").put("title", "漫画")
                .put("cover", heic)))
            .put("pagination", JSONObject().put("pageNum", 1).put("totalPages", 1))
        val json = """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":1}}};</script>
            <script data-fn-name="r" data-fn-args="${JSONArray().put("rank_hot-comic-drama/page").put("content").put(payload).toString().replace("&", "&amp;").replace("\"", "&quot;")}"></script>"""
        assertEquals(expected, repository { json }.home(1, ContentType.COMIC).items.single().cover)

        val rendered = """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":1}}};</script>
            <article aria-labelledby="rank-title-123"><h2 id="rank-title-123">漫画</h2>
            <img src="$heic"></article>
            <nav aria-label="榜单分页"><span aria-current="page">1</span></nav>"""
        assertEquals(expected, repository { rendered }.home(1, ContentType.COMIC).items.single().cover)
    }

    @Test fun relatedCoversAreRewritten() {
        val raw = JSONObject().put("code", 0).put("data", JSONArray().put(JSONObject()
            .put("show_type", 547)
            .put("video_data", JSONArray().put(JSONObject()
                .put("series_id", "222").put("title", "相关剧").put("content_type", 1004)
                .put("episode_cnt", 10).put("cover", heic))))).toString()
        assertEquals(expected, RelatedWorks.parse(raw, "111").single().series.cover)
    }

    private val hash = "033873814a4b74d4365620010d16548e"

    private fun client(respond: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor { chain -> respond(chain.request()) }.build()

    private fun response(request: Request, body: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody()).build()

    private fun repository(respond: (Request) -> String) = ContentRepository(client { request ->
        response(request, respond(request))
    })

    private fun accountRepository(respond: (Request) -> Response) = AccountRepository(client(respond))
        .also {
            it.restoreSession(AuthSession(
                mapOf("sessionid" to "session-secret"),
                "token-secret",
                AuthUser("uid-test", "测试账号", accountId = "123456789012345"),
            ))
        }
}
