package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AuthTest {
    private fun response(
        request: Request,
        body: String,
        headers: List<Pair<String,String>> = emptyList(),
        code: Int = 200
    ): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(code).message(if(code==200) "OK" else "Redirect").apply {
            headers.forEach { (name,value) -> addHeader(name,value) }
        }.body(body.toResponseBody()).build()

    private fun client(respond: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor { chain -> respond(chain.request()) }.build()

    private fun qrResponse() =
        """{"message":"success","data":{"token":"qr-token","qrcode":"data:image/png;base64,AAAA","expire_time":1700000180}}"""

    @Test fun createQrUsesWebEndpointAndSendsCsrfOnCheck() {
        var createRequest: Request?=null
        var checkRequest: Request?=null
        val repo=AccountRepository(client { request ->
            when(request.url.encodedPath) {
                "/passport/web/get_qrcode/" -> {
                    createRequest=request
                    response(request,qrResponse(),listOf(
                        "Set-Cookie" to "passport_csrf_token=csrf-token; Domain=hongguoduanju.com; Path=/"
                    ))
                }
                "/passport/web/check_qrconnect/" -> {
                    checkRequest=request
                    response(request,"""{"message":"success","data":{"status":"new"}}""")
                }
                else -> error("unexpected ${request.url}")
            }
        },{ 1_700_000_000_000L })

        val qr=repo.createLoginQr()
        val create=createRequest ?: error("create request missing")
        assertEquals("GET",create.method)
        assertEquals("513755",create.url.queryParameter("aid"))
        assertEquals("https://hongguoduanju.com/",create.url.queryParameter("next"))
        assertEquals("web",create.url.queryParameter("account_sdk_source"))
        assertEquals("2.4.13",create.url.queryParameter("passport_jssdk_version"))
        assertNull(create.url.queryParameter("a_bogus"))
        assertNull(create.url.queryParameter("msToken"))
        assertEquals("data:image/png;base64,AAAA",qr.image)
        assertEquals(1_700_000_180_000L,qr.expiresAt)
        assertFalse(qr.toString().contains("qr-token"))

        val poll=repo.pollLoginQr()
        assertEquals(LoginQrStatus.NEW,poll.status)
        val check=checkRequest ?: error("check request missing")
        assertEquals("GET",check.method)
        assertEquals("qr-token",check.url.queryParameter("token"))
        assertEquals("513755",check.url.queryParameter("aid"))
        assertTrue(check.header("Cookie").orEmpty().contains("passport_csrf_token=csrf-token"))
        assertEquals("csrf-token",check.header("x-tt-passport-csrf-token"))
    }

    @Test fun pollMapsScannedAndExpiredAndStopsAfterExpiry() {
        var status="scanned"
        val repo=AccountRepository(client { request ->
            when(request.url.encodedPath) {
                "/passport/web/get_qrcode/" -> response(request,qrResponse())
                "/passport/web/check_qrconnect/" -> response(request,"""{"message":"success","data":{"status":"$status"}}""")
                else -> error("unexpected ${request.url}")
            }
        },{ 1_700_000_000_000L })

        repo.createLoginQr()
        assertEquals(LoginQrStatus.SCANNED,repo.pollLoginQr().status)
        status="expired"
        assertEquals(LoginQrStatus.EXPIRED,repo.pollLoginQr().status)
        assertThrows(java.io.IOException::class.java) { repo.pollLoginQr() }
    }

    @Test fun confirmedQrFollowsRedirectAndStoresSession() {
        var landingRequest: Request?=null
        var userInfoRequest: Request?=null
        val repo=AccountRepository(client { request ->
            when(request.url.encodedPath) {
                "/passport/web/get_qrcode/" -> response(request,qrResponse())
                "/passport/web/check_qrconnect/" -> response(request,
                    """{"message":"success","data":{"status":"confirmed","redirect_url":"https://hongguoduanju.com/landing?ticket=test"}}""",
                    listOf(
                        "Set-Cookie" to "sessionid=web-session; Domain=hongguoduanju.com; Path=/",
                        "Set-Cookie" to "uid_tt=uid-web; Domain=hongguoduanju.com; Path=/",
                        "X-Tt-Token" to "web-token"
                    ))
                "/landing" -> {
                    landingRequest=request
                    response(request,"",listOf(
                        "Location" to "https://hongguoduanju.com/",
                        "Set-Cookie" to "sid_tt=sid-web; Domain=hongguoduanju.com; Path=/"
                    ),302)
                }
                "/" -> response(request,"ok")
                "/reading/user/info/v" -> {
                    userInfoRequest=request
                    response(request,"""{"code":0,"data":{"req_id":"123456789012345","user_name":"扫码账号","avatar_url":"https://example.com/avatar.jpg","description":"账号简介","mobile":"19900000000"}}""")
                }
                else -> error("unexpected ${request.url}")
            }
        },{ 1_700_000_000_000L })

        repo.createLoginQr()
        val poll=repo.pollLoginQr()
        assertEquals(LoginQrStatus.CONFIRMED,poll.status)
        val session=poll.session ?: error("session missing")
        assertEquals("扫码账号",session.user.name)
        assertEquals("uid-web",session.user.id)
        assertEquals("123456789012345",session.user.accountId)
        assertEquals("199******00",session.user.mobile)
        assertEquals("web-token",session.token)
        assertEquals("web-session",session.cookies["sessionid"])
        assertEquals("sid-web",session.cookies["sid_tt"])
        assertFalse(poll.toString().contains("web-token"))

        val landing=landingRequest ?: error("landing request missing")
        assertTrue(landing.header("Cookie").orEmpty().contains("sessionid=web-session"))
        val info=userInfoRequest ?: error("user info request missing")
        assertTrue(info.header("Cookie").orEmpty().contains("sessionid=web-session"))
        assertEquals("web-token",info.header("X-Tt-Token"))
        assertEquals("73932",info.url.queryParameter("version_code"))

        val restored=AuthSession.fromJson(session.toJson())
        assertEquals(session,restored)
        assertEquals(session,repo.currentSession())
    }

    @Test fun profileFailureKeepsGenericQrSession() {
        val repo=AccountRepository(client { request ->
            when(request.url.encodedPath) {
                "/passport/web/get_qrcode/" -> response(request,qrResponse())
                "/passport/web/check_qrconnect/" -> response(request,
                    """{"message":"success","data":{"status":"confirmed","redirect_url":"https://hongguoduanju.com/landing"}}""",
                    listOf(
                        "Set-Cookie" to "sessionid=web-session; Domain=hongguoduanju.com; Path=/",
                        "Set-Cookie" to "uid_tt=uid-web; Domain=hongguoduanju.com; Path=/"
                    ))
                "/landing" -> response(request,"ok")
                "/" -> response(request,"ok")
                "/reading/user/info/v" -> response(request,"""{"code":500,"message":"服务繁忙"}""")
                else -> error("unexpected ${request.url}")
            }
        },{ 1_700_000_000_000L })

        repo.createLoginQr()
        val poll=repo.pollLoginQr()
        assertEquals(LoginQrStatus.CONFIRMED,poll.status)
        val session=poll.session ?: error("generic session missing")
        assertEquals("",session.user.name)
        assertEquals("uid-web",session.user.id)
        assertEquals("web-session",session.cookies["sessionid"])
    }

    @Test fun expiredProfileClearsQrSession() {
        val repo=AccountRepository(client { request ->
            when(request.url.encodedPath) {
                "/passport/web/get_qrcode/" -> response(request,qrResponse())
                "/passport/web/check_qrconnect/" -> response(request,
                    """{"message":"success","data":{"status":"confirmed","redirect_url":"https://hongguoduanju.com/landing"}}""",
                    listOf(
                        "Set-Cookie" to "sessionid=web-session; Domain=hongguoduanju.com; Path=/",
                        "Set-Cookie" to "uid_tt=uid-web; Domain=hongguoduanju.com; Path=/"
                    ))
                "/landing" -> response(request,"ok")
                "/" -> response(request,"ok")
                "/reading/user/info/v" -> response(request,
                    """{"code":1001,"message":"未登录","data":{"description":"登录已过期"}}""")
                else -> error("unexpected ${request.url}")
            }
        },{ 1_700_000_000_000L })

        repo.createLoginQr()
        assertThrows(java.io.IOException::class.java) { repo.pollLoginQr() }
        assertNull(repo.currentSession())
    }

    @Test fun qrFailureMessageRedactsToken() {
        val repo=AccountRepository(client { request ->
            response(request,"""{"message":"token=qr-secret 已失效","data":{}}""")
        },{ 1_700_000_000_000L })

        val error=assertThrows(java.io.IOException::class.java) { repo.createLoginQr() }
        assertFalse(error.message.orEmpty().contains("qr-secret"))
        assertTrue(error.message.orEmpty().contains("token=***"))
    }

    @Test fun genericQrSessionRoundTrips() {
        val session=AuthSession(mapOf("sessionid" to "web-session"),"",AuthUser("uid-web",""))
        assertEquals(session,AuthSession.fromJson(session.toJson()))
    }
}
