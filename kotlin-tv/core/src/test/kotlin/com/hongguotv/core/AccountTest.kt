package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountTest {
    private fun response(
        request: Request,
        body: String,
        headers: List<Pair<String,String>> = emptyList(),
        code: Int = 200
    ): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(code).message(if(code==200) "OK" else "Error").apply {
            headers.forEach { (name,value) -> addHeader(name,value) }
        }.body(body.toResponseBody()).build()

    private fun client(respond: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor { chain -> respond(chain.request()) }.build()

    private fun session() = AuthSession(
        mapOf("sessionid" to "session-secret"),
        "token-secret",
        AuthUser("uid-test","测试账号",accountId="123456789012345")
    )

    private fun repository(respond: (Request) -> Response) = AccountRepository(client(respond),{ 1_700_000_000_000L })
        .also { it.restoreSession(session()) }

    private fun bodyText(request: Request): String {
        val body=request.body ?: return ""
        val buffer=Buffer(); body.writeTo(buffer); return buffer.readUtf8()
    }

    @Test fun historyMergesBookTypesAndKeepsRemoteProgress() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            when(request.url.queryParameter("book_type")) {
                "2" -> response(request,"""{"code":0,"data":{"data_list":[{
                    "book_id_str":"111","book_name":"短剧一","thumb_url":"https://example.com/one.jpg",
                    "vid":"9001","vid_index":2,"current_play_position":5000,"duration":100000,
                    "read_timestamp_ms":200,"episode_cnt":12,
                    "category_list":[{"name":"都市"}]
                }]}}""")
                "3" -> response(request,"""{"code":0,"data":{"data_list":[{
                    "book_id_str":"999","book_name":"外层标题","video_data":{
                        "series_id":"222","series_title":"漫剧一",
                        "series_cover":"https://example.com/two.jpg","series_intro":"简介"
                    },"vid":"9002","vid_index":0,"read_timestamp_ms":300
                }]}}""")
                "4","5" -> response(request,"""{"code":0,"data":{"data_list":[]}}""")
                else -> error("unexpected book_type")
            }
        }

        val page=repo.accountHistory()
        assertEquals(listOf("222","111"),page.items.map { it.id })
        assertEquals("漫剧一",page.items[0].title)
        assertEquals("https://example.com/two.jpg",page.items[0].cover)
        assertEquals("9002",page.history[0].episodeId)
        assertEquals(1,page.history[1].episodeIndex)
        assertEquals(5000,page.history[1].position)
        assertEquals("全 12 集",page.items[1].badge)
        assertEquals("都市",page.items[1].tags)

        assertEquals(setOf("2","3","4","5"),requests.mapNotNull { it.url.queryParameter("book_type") }.toSet())
        assertTrue(requests.all { it.url.queryParameter("version_code")=="73932" })
        assertTrue(requests.all { it.header("Cookie").orEmpty().contains("sessionid=session-secret") })
        assertTrue(requests.all { it.header("X-Tt-Token")=="token-secret" })
        assertTrue(requests.all { it.header("lc")=="101" })
    }

    @Test fun favoritesUsesAccountShelfAndTargetUser() {
        var request: Request?=null
        val repo=repository { current ->
            request=current
            response(current,"""{"code":0,"data":{"video_shelf_info":[{
                "book_id_str":"333","book_name":"收藏剧","thumb_url":"https://example.com/shelf.jpg",
                "episode_cnt":8,"categories":[{"name":"悬疑"}]
            },{
                "video_data":{"series_id":"334","series_title":"嵌套收藏","series_cover":"https://example.com/nested.jpg"},
                "episode_cnt":6
            }]}}""")
        }

        val page=repo.accountFavorites()
        assertEquals(2,page.items.size)
        assertEquals("333",page.items[0].id)
        assertEquals("收藏剧",page.items[0].title)
        assertEquals("https://example.com/shelf.jpg",page.items[0].cover)
        assertEquals("全 8 集",page.items[0].badge)
        assertEquals("334",page.items[1].id)
        assertEquals("嵌套收藏",page.items[1].title)
        assertEquals("https://example.com/nested.jpg",page.items[1].cover)
        val actual=request ?: error("request missing")
        assertEquals("/reading/bookapi/bookshelf/video/list/v",actual.url.encodedPath)
        assertEquals("123456789012345",actual.url.queryParameter("target_user_id"))
        assertEquals("session-secret",actual.header("Cookie").orEmpty().substringAfter("sessionid=").substringBefore(";"))
        assertEquals("token-secret",actual.header("X-Tt-Token"))
    }

    @Test fun reservationsMergeOnlineAndOfflineAndKeepExpectedTime() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            when(request.url.queryParameter("is_online")) {
                "false" -> response(request,"""{"code":0,"data":{"subscribe_items":[{
                    "item_id":"444","item_type":14,"name":"待播剧","cover":"https://example.com/wait.jpg",
                    "item_desc":"简介","is_online":false,"expected_publish_time":1793000000,
                    "expected_rec_text":"11月3日上线","tag_info":{"text":"都市"},
                    "categories":[{"name":"短剧"}],"has_subscribed":1
                }]}}""")
                "true" -> response(request,"""{"code":0,"data":{"subscribe_items":[{
                    "item_id":"555","item_type":14,"name":"已播剧","is_online":1,
                    "has_subscribed":true
                }]}}""")
                else -> error("unexpected online flag")
            }
        }

        val page=repo.accountReservations()
        assertEquals(listOf("444","555"),page.items.map { it.id })
        assertTrue(page.items[0].preview)
        assertFalse(page.items[1].preview)
        assertTrue(page.reservations[0].subscribed)
        assertEquals(1793000000L,page.reservations[0].expectedAt)
        assertEquals("11月3日上线 · 都市",page.items[0].badge)
        assertEquals("短剧",page.items[0].tags)
        assertEquals(setOf("false","true"),requests.mapNotNull { it.url.queryParameter("is_online") }.toSet())
        assertTrue(requests.all { it.url.queryParameter("tab_type")=="13" })
    }

    @Test fun favoritesRequireAccountIdInsteadOfGuessing() {
        val repo=AccountRepository(client { error("favorites must not call the API without an account id") },
            { 1_700_000_000_000L }).also {
            it.restoreSession(AuthSession(mapOf("sessionid" to "session-secret"),"token-secret",
                AuthUser("uid-test","测试账号")))
        }
        val error=assertThrows(java.io.IOException::class.java) { repo.accountFavorites() }
        assertTrue(error.message.orEmpty().contains("用户标识"))
    }

    @Test fun reserveAndCancelUseObservedOpTypesAndBody() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            response(request,"""{"code":0,"data":{}}""")
        }

        assertTrue(repo.reserve("444"))
        assertFalse(repo.cancelReservation("444"))
        assertEquals(2,requests.size)

        val reserve=JSONObject(bodyText(requests[0]))
        assertEquals(444L,reserve.getLong("item_id"))
        assertEquals(1,reserve.getInt("op_type"))
        assertEquals(14,reserve.getInt("item_type"))
        assertEquals("444",reserve.getString("item_id_str"))
        assertEquals(0,reserve.getInt("wish_list_all_del"))
        assertEquals("MainFragmentActivity,ShortSeriesActivity",
            reserve.getJSONObject("shark_param").getString("page_list"))
        assertEquals("/reading/bookapi/search/uncover_subscribe/v:version/",requests[0].url.encodedPath)
        assertEquals("POST",requests[0].method)
        assertEquals("101",requests[0].header("lc"))
        assertTrue(requests[0].header("Cookie").orEmpty().contains("sessionid=session-secret"))
        assertEquals("token-secret",requests[0].header("X-Tt-Token"))

        val cancel=JSONObject(bodyText(requests[1]))
        assertEquals(2,cancel.getInt("op_type"))
        assertEquals("444",cancel.getString("item_id_str"))
    }

    @Test fun accountIdRoundTripsWithSession() {
        val user=AuthUser("uid","名字",accountId="123456789012345")
        val restored=AuthSession.fromJson(AuthSession(mapOf("sessionid" to "x"),"token",user).toJson())
        assertEquals("123456789012345",restored?.user?.accountId)
    }

    @Test fun accountFailureDoesNotExposeSessionValues() {
        val repo=repository { request ->
            response(request,"""{"code":1001,"message":"未登录 sessionid=session-secret token=token-secret","data":{}}""")
        }
        val error=assertThrows(java.io.IOException::class.java) { repo.accountFavorites() }
        assertFalse(error.message.orEmpty().contains("session-secret"))
        assertFalse(error.message.orEmpty().contains("token-secret"))
    }
}
