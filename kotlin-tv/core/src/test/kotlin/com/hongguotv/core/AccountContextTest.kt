package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AccountContextTest {
    private fun session(account: String="700",cookies: Map<String,String> = mapOf("sessionid" to "secret"))=
        AuthSession(cookies,"secret-token",AuthUser("fixture","name",accountId=account))
    @Test fun identicalRestoreAndMetadataDoNotInvalidateButLogoutAndConflictingOwnerDo() {
        val repo=AccountRepository(); val initial=session(); repo.restoreSession(initial)
        val context=repo.captureAccount()!!
        repo.restoreSession(initial.copy(user=initial.user.copy(name="changed")))
        assertTrue(repo.isCurrent(context))
        repo.logout(); repo.restoreSession(initial); assertFalse(repo.isCurrent(context))
        val replacement=repo.captureAccount()!!
        repo.restoreSession(session("800")); assertFalse(repo.isCurrent(replacement))
    }
    @Test fun unresolvedOwnerMayResolveButCannotAuthorizeFavoriteRequests() {
        val repo=AccountRepository(); repo.restoreSession(session("0"))
        val unknown=repo.captureAccount()!!; assertNull(unknown.owner)
        assertThrows(java.io.IOException::class.java) { repo.writeFavorite(unknown,"11",true) }
        repo.restoreSession(session("700")); assertTrue(repo.isCurrent(unknown))
        assertNotNull(repo.captureAccount()!!.owner)
    }
    @Test fun contextCopiesCookiesAndNeverPrintsCredentialsAndHashEncodingIsUnambiguous() {
        val cookies=linkedMapOf("sessionid" to "secret"); val repo=AccountRepository()
        repo.restoreSession(session(cookies=cookies)); val context=repo.captureAccount()!!
        cookies["sessionid"]="different"
        assertEquals("secret",context.session.cookies["sessionid"])
        assertFalse(context.toString().contains("secret")); assertTrue(repo.isCurrent(context))
        repo.restoreSession(session(cookies=linkedMapOf("a" to "b;c=d")))
        val one=repo.captureAccount()!!.credentialOwner
        repo.restoreSession(session(cookies=linkedMapOf("a" to "b","c" to "d")))
        assertNotEquals(one,repo.captureAccount()!!.credentialOwner)
    }
    @Test fun profileResponseCannotReviveAnOldEpochAfterIdenticalRelogin() {
        lateinit var repo: AccountRepository; val initial=session()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            repo.logout(); repo.restoreSession(initial)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"code":0,"data":{"user_name":"old-response","user_id":"700"}}""".toResponseBody()).build()
        }.build()
        repo=AccountRepository(http); repo.restoreSession(initial)
        assertThrows(java.io.IOException::class.java) { repo.refreshUser() }
        assertEquals("name",repo.currentSession()!!.user.name)
    }
    @Test fun ordinaryProfileRefreshPreservesItsContextAndReturnsOnlyItsCapturedSession() {
        val http=OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request())
            .protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body("""{"code":0,"data":{"user_name":"refreshed","user_id":"700"}}""".toResponseBody()).build() }.build()
        val repo=AccountRepository(http); repo.restoreSession(session()); val context=repo.captureAccount()!!
        assertEquals("refreshed",repo.refreshUser().user.name); assertTrue(repo.isCurrent(context))
    }
    @Test fun staleQrExpiredProfileFailureCannotLogOutReplacementLogin() {
        lateinit var repo: AccountRepository; val replacement=session().copy(user=AuthUser("new","new",accountId="800"))
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val response=when(request.url.encodedPath) {
                "/passport/web/get_qrcode/" -> """{"data":{"token":"fixture","qrcode":"data:image/png;base64,A","expire_time":2000000000}}"""
                "/passport/web/check_qrconnect/" -> """{"data":{"status":"confirmed"}}"""
                "/reading/user/info/v" -> { repo.logout(); repo.restoreSession(replacement); """{"code":1001,"message":"登录已过期"}""" }
                else -> "ok"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .apply { if(request.url.encodedPath=="/passport/web/check_qrconnect/") addHeader("Set-Cookie","sessionid=qr; Domain=hongguoduanju.com; Path=/") }
                .body(response.toResponseBody()).build()
        }.build()
        repo=AccountRepository(http); repo.createLoginQr()
        assertThrows(java.io.IOException::class.java) { repo.pollLoginQr() }
        assertEquals(replacement,repo.currentSession())
    }
    @Test fun capturedProfileRefreshCannotAdoptAReplacementSessionBeforeDispatch() {
        var dispatched=0
        val repo=AccountRepository(OkHttpClient.Builder().addInterceptor { chain ->
            dispatched++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"code":0,"data":{"user_name":"wrong-flow","user_id":"800"}}""".toResponseBody()).build()
        }.build())
        repo.restoreSession(session()); val captured=repo.captureAccount()!!
        repo.restoreSession(session("800"))
        assertThrows(java.io.IOException::class.java) { repo.refreshUser(captured) }
        assertEquals(0,dispatched)
        assertEquals("800",repo.currentSession()!!.user.accountId)
    }
}
