package com.hongguotv.nativeapp

import com.hongguotv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[21])
class FavoriteProtocolPlatformTest {
    private fun repository(raw: String)=AccountRepository(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(raw.toResponseBody()).build()
    }.build()).also { it.restoreSession(AuthSession(mapOf("sessionid" to "fixture"),"token",AuthUser("7","fixture",accountId="700"))) }

    @Test fun androidJsonCannotCoerceMissingStringOrFractionalCodesIntoSuccess() {
        listOf("{}","""{"code":"0"}""","""{"code":0.5}""","""{"code":1e-400}""","""{"code":null}""","""{"code":1}""").forEach { raw ->
            val repo=repository(raw)
            assertThrows(raw,IOException::class.java) { repo.writeFavorite(repo.captureAccount()!!,"11",true) }
        }
        val valid=repository("""{"code":0}""")
        valid.writeFavorite(valid.captureAccount()!!,"11",true)
    }

    @Test fun androidJsonCannotCoerceMalformedMembershipIdsAndPartialRowsRemainUseful() {
        listOf("""{"video_data":42,"book_id":"11"}""","""{"video_data":{"series_id":12},"book_id":"11"}""",
            """{"video_data":{"series_id":"bad"},"book_id":"11"}""").forEach { row ->
            val repo=repository("""{"code":0,"data":{"video_shelf_info":[$row]}}""")
            assertThrows(IOException::class.java) { repo.readFavoriteMembership(repo.captureAccount()!!) }
        }
        val valid=repository("""{"code":0,"data":{"video_shelf_info":[{"video_data":{"series_id":"11"}},{"book_id":"12"}]}}""")
        val result=valid.readFavoriteMembership(valid.captureAccount()!!)
        assertEquals(setOf("11","12"),result.ids)
        assertFalse(result.complete)
    }
}
