package com.hongguotv.core
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AccountIsolationTest {
    @Test fun anonymousContentNeverInheritsAccountCookies() {
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Cookie")); assertNull(chain.request().header("X-Tt-Token"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"search_tabs":[{"tab_type":11,"data":[],"has_more":false}]}""".toResponseBody()).build()
        }.build()
        val content=ContentRepository(http); val accounts=AccountRepository(content)
        accounts.restoreSession(AuthSession(mapOf("sessionid" to "secret"),"secret-token",AuthUser("user","")))
        assertTrue(content.search("测试").items.isEmpty())
    }
    @Test fun profileResponseAfterLogoutCannotRestoreSession() {
        lateinit var repo: AccountRepository
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            repo.logout()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":{"user_name":"late"}}""".toResponseBody()).build()
        }.build()
        repo=AccountRepository(http)
        repo.restoreSession(AuthSession(mapOf("sessionid" to "secret"),"",AuthUser("user","")))
        assertThrows(java.io.IOException::class.java) { repo.refreshUser() }
        assertNull(repo.currentSession())
    }
    @Test fun profileResponseForPreviousAccountCannotOverwriteNewAccount() {
        lateinit var repo: AccountRepository
        val newer=AuthSession(mapOf("sessionid" to "new"),"new-token",AuthUser("new-user","new-name"))
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("sessionid=old",chain.request().header("Cookie"))
            repo.restoreSession(newer)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":{"user_name":"old-name"}}""".toResponseBody()).build()
        }.build()
        repo=AccountRepository(http)
        repo.restoreSession(AuthSession(mapOf("sessionid" to "old"),"old-token",AuthUser("old-user","")))
        assertThrows(java.io.IOException::class.java) { repo.refreshUser() }
        assertEquals(newer,repo.currentSession())
    }

}
