package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class FavoriteProtocolTest {
    private val auth=AuthSession(mapOf("sessionid" to "captured"),"captured-token",AuthUser("fixture","name",accountId="700"))
    private fun repo(response: String,inspect: (Request)->Unit = {}): AccountRepository = AccountRepository(OkHttpClient.Builder().addInterceptor { chain ->
        inspect(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(response.toResponseBody()).build()
    }.build()).also { it.restoreSession(auth) }
    @Test fun mutationIsSignedCapturedAndOneShotWithExactAddRemoveFields() {
        val seen=mutableListOf<JSONObject>()
        val repo=repo("""{"code":0}""") { request ->
            assertEquals("sessionid=captured",request.header("Cookie")); assertEquals("captured-token",request.header("X-Tt-Token"))
            assertEquals("/reading/bookapi/bookshelf/video/update/v1/",request.url.encodedPath)
            assertEquals("73932",request.url.queryParameter("version_code"))
            assertNotNull(request.header("X-Argus")); assertNotNull(request.header("X-Ladon"))
            assertTrue(request.body!!.isOneShot())
            val bytes=Buffer().also { request.body!!.writeTo(it) }.readByteArray()
            val md5=java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) }
            assertEquals(md5,request.header("x-ss-stub"))
            val gorgon=request.header("x-gorgon")!!
            val random=gorgon.substring(4,6).toInt(16) or (gorgon.substring(6,8).toInt(16) shl 8)
            assertEquals(gorgon,Signer().gorgon(request.url.encodedQuery!!,bytes,request.header("x-khronos")!!.toInt(),random))
            seen+=JSONObject(String(bytes,Charsets.UTF_8))
        }
        val context=repo.captureAccount()!!; repo.writeFavorite(context,"11",true); repo.writeFavorite(context,"11",false)
        seen.forEachIndexed { index,root ->
            assertFalse(root.getBoolean("is_cancelled")); val row=root.getJSONArray("update_bookshelf_video_list").getJSONObject(0)
            assertEquals("11",row.getString("book_id")); assertEquals(2,row.getInt("book_type"))
            assertTrue(row.get("modify_time") is Number); assertEquals(index,row.getInt("video_shelf_operate_type"))
        }
        assertEquals("MainFragmentActivity",seen[0].getJSONObject("shark_extra").getString("enter_from"))
        assertEquals("",seen[1].getJSONArray("update_bookshelf_video_list").getJSONObject(0).getString("group_name"))
    }
    @Test fun missingStringFractionalNullAndNonzeroSuccessCodesFailClosed() {
        listOf("{}","""{"code":"0"}""","""{"code":0.5}""","""{"code":1e-400}""","""{"code":null}""","""{"code":1}""").forEach { raw ->
            val repo=repo(raw); assertThrows(raw,IOException::class.java) { repo.writeFavorite(repo.captureAccount()!!,"11",true) }
        }
    }
    @Test fun rawMembershipDoesNotRequireTitlesAndIsNeverExhaustive() {
        val repo=repo("""{"code":0,"data":{"video_shelf_info":[{"video_data":{"series_id":"11"}},{"series_id":"12"},{"book_id":"13"}]}}""") { request ->
            assertEquals("/reading/bookapi/bookshelf/video/info/v1/",request.url.encodedPath)
            assertEquals("72232",request.url.queryParameter("version_code"))
            assertEquals("7.2.2.32",request.url.queryParameter("version_name"))
        }
        val membership=repo.readFavoriteMembership(repo.captureAccount()!!)
        assertEquals(setOf("11","12","13"),membership.ids); assertFalse(membership.complete)
        listOf("""{"code":0}""","""{"code":0,"data":{}}""","""{"code":0,"data":{"video_shelf_info":{}}}""").forEach { raw ->
            val bad=repo(raw); assertThrows(IOException::class.java) { bad.readFavoriteMembership(bad.captureAccount()!!) }
        }
        val empty=repo("""{"code":0,"data":{"video_shelf_info":null}}""")
        assertTrue(empty.readFavoriteMembership(empty.captureAccount()!!).ids.isEmpty())
    }
    @Test fun malformedPresentMembershipFieldsCannotBeBypassedByFallbackIds() {
        listOf("""{"video_data":42,"book_id":"11"}""","""{"video_data":{"series_id":12},"book_id":"11"}""",
            """{"video_data":{"series_id":"bad"},"book_id":"11"}""").forEach { row ->
            val repo=repo("""{"code":0,"data":{"video_shelf_info":[$row]}}""")
            assertThrows(IOException::class.java) { repo.readFavoriteMembership(repo.captureAccount()!!) }
        }
    }
    @Test fun displayReadWithUnresolvedOwnerNeverDispatches() {
        val dispatches=AtomicInteger()
        val repo=repo("""{"code":0,"data":{}}""") { dispatches.incrementAndGet() }
        listOf("0","malformed","").forEach { account ->
            repo.logout()
            repo.restoreSession(auth.copy(user=auth.user.copy(accountId=account)))
            assertThrows(IOException::class.java) { repo.accountFavorites(repo.captureAccount()!!) }
        }
        assertEquals(0,dispatches.get())
    }
    @Test fun changedContextDiscardsEvenSuccessfulMutationResponse() {
        lateinit var repo: AccountRepository
        repo=repo("""{"code":0}""") { repo.logout(); repo.restoreSession(auth) }
        val context=repo.captureAccount()!!
        assertThrows(IOException::class.java) { repo.writeFavorite(context,"11",true) }
    }
    @Test fun realFollowUpLayerNeverRepeats503OrRedirectsOneShotMutation() {
        for(code in listOf(503,302)) {
            val requests=AtomicInteger()
            val server=java.net.ServerSocket(0,5,java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout=1500 }
            val worker=Thread {
                server.use { listener ->
                    while(true) try {
                        listener.accept().use { socket ->
                            socket.soTimeout=1500; val input=socket.getInputStream().bufferedReader()
                            var length=0
                            while(true) { val line=input.readLine() ?: break; if(line.isEmpty()) break
                                if(line.startsWith("Content-Length:",true)) length=line.substringAfter(':').trim().toInt() }
                            repeat(length) { input.read() }; requests.incrementAndGet()
                            socket.getOutputStream().write(("HTTP/1.1 $code Fixture\r\nContent-Length: 0\r\nConnection: close\r\n"+
                                if(code==503) "Retry-After: 0\r\n\r\n" else "Location: http://127.0.0.1:${server.localPort}/redirected\r\n\r\n").toByteArray())
                        }
                    } catch(_: java.net.SocketTimeoutException) { break } catch(_: java.net.SocketException) { break }
                }
            }.apply { isDaemon=true; start() }
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:${server.localPort}/favorite").build())
            }.build()
            val repo=AccountRepository(client); repo.restoreSession(auth)
            assertThrows(IOException::class.java) { repo.writeFavorite(repo.captureAccount()!!,"11",true) }
            worker.join(5000); assertFalse(worker.isAlive); assertEquals("HTTP $code",1,requests.get())
        }
    }
}
