package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AccountHistoryUploadTest {
    private fun session(id: String="a")=AuthSession(mapOf("sessionid" to "fixture-cookie"),"fixture-token",AuthUser(id,"fixture"))
    private fun row()=HistoryCheckpoint(BackupProgress(Series("123","fixture",bookType=2),"456",1,12500,60000,false,1700000000000),2,10)
    @Test fun uploadUsesCapturedSessionAndMillisecondProgressWithOneBasedEpisode() {
        var calls=0
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request(); calls++
            assertEquals("/reading/bookapi/read_history/update/v",request.url.encodedPath)
            assertEquals("POST",request.method)
            assertEquals("sessionid=fixture-cookie",request.header("Cookie"))
            assertEquals("fixture-token",request.header("X-Tt-Token"))
            val buffer=Buffer(); request.body!!.writeTo(buffer)
            val data=JSONObject(buffer.readUtf8()).getJSONArray("update_datas").getJSONObject(0)
            assertEquals("123",data.get("book_id").toString())
            assertEquals(2,data.getInt("vid_index")); assertEquals(12500L,data.getLong("current_play_position"))
            assertFalse(data.getBoolean("is_delete"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{\"code\":0}".toResponseBody()).build()
        }.build()
        val repo=AccountRepository(http); val session=session(); repo.restoreSession(session)
        repo.uploadHistory(session,listOf(row())); assertEquals(1,calls)
        repo.restoreSession(session("b"))
        assertThrows(IOException::class.java) { repo.uploadHistory(session,listOf(row())) }
        assertEquals(1,calls)
        repo.logout(); assertThrows(IOException::class.java) { repo.uploadHistory(session,listOf(row())) }
    }
    @Test fun historyReadNeverMixesAccountsDuringSwitch() {
        var repo: AccountRepository?=null
        var calls=0
        val captured=session()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("sessionid=fixture-cookie",chain.request().header("Cookie"))
            assertEquals("fixture-token",chain.request().header("X-Tt-Token"))
            repo!!.restoreSession(AuthSession(mapOf("sessionid" to "other-cookie"),"other-token",AuthUser("b","other")))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"code\":0,\"data\":{\"data_list\":[]}}".toResponseBody()).build()
        }.build()
        repo=AccountRepository(http); repo.restoreSession(captured)
        assertThrows(IOException::class.java) { repo.accountHistory(requireAllTypes=true,session=captured) }
        assertEquals(1,calls)
        assertThrows(IOException::class.java) { repo.accountHistory(requireAllTypes=true,session=captured) }
        assertEquals(1,calls)
    }
    @Test fun rejectsUnconfirmedSuccessAndPreservesExactLargeIdentifiers() {
        val repo=AccountRepository(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()); val session=session(); repo.restoreSession(session)
        assertThrows(IOException::class.java) { repo.uploadHistory(session,listOf(row())) }
        val checkpoint=row().let { it.copy(progress=it.progress.copy(series=Series("12345678901234567890","fixture"))) }
        assertEquals("12345678901234567890",checkpoint.json().get("book_id").toString())
        assertEquals(checkpoint,HistoryCheckpoint.restore(checkpoint.stored()))
    }
}
