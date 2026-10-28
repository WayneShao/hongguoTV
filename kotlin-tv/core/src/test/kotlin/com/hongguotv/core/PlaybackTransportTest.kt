package com.hongguotv.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class PlaybackTransportTest {
    private fun serve(slow: ()->Boolean, block: (String)->Unit) {
        val data=ByteArray(1024*1024) { (it%239).toByte() }
        java.nio.ByteBuffer.wrap(data).putInt(8).put("ftyp".toByteArray()).putInt(8).put("moov".toByteArray())
        val pool=Executors.newCachedThreadPool()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.executor=pool
        server.createContext("/video") { exchange ->
            try {
                val range=exchange.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-').map(String::toInt)
                val start=range[0];val end=minOf(data.lastIndex,range[1])
                exchange.responseHeaders.add("Content-Range","bytes $start-$end/${data.size}")
                exchange.sendResponseHeaders(206,(end-start+1).toLong())
                exchange.responseBody.use { out ->
                    if(slow()) { out.write(data,start,1);out.flush();Thread.sleep(350);out.write(data,start+1,end-start) }
                    else out.write(data,start,end-start+1)
                }
            } catch(_: Exception) {} finally { exchange.close() }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}/video") }
        finally {server.stop(0);pool.shutdownNow()}
    }
    @Test fun progressingReadOutlivesInheritedTotalTimeout() {
        var slow=false
        serve({slow}) { url ->
            val http=OkHttpClient.Builder().callTimeout(100,TimeUnit.MILLISECONDS).readTimeout(2,TimeUnit.SECONDS).build()
            RemoteVideo(http,StreamInfo(url,null,"480P")).use { video ->
                video.prepare();slow=true
                video.openRead(40000,100).use { assertEquals(100,it.readBytes().size) }
                assertEquals(100L,video.deliveredBytes)
            }
        }
    }
    @Test fun stalledReadStillTimesOutWithoutDestroyingPreparedResource() {
        var slow=false
        serve({slow}) { url ->
            val http=OkHttpClient.Builder().readTimeout(100,TimeUnit.MILLISECONDS).build()
            RemoteVideo(http,StreamInfo(url,null,"480P")).use { video ->
                video.prepare();slow=true
                assertThrows(java.net.SocketTimeoutException::class.java) { video.openRead(40000,100).use {it.readBytes()} }
                assertTrue(video.reusable);slow=false
                video.openRead(40000,100).use { assertEquals(100,it.readBytes().size) }
            }
        }
    }
    @Test fun onlyExpiredOrChangedVideoRequiresNewModel() {
        assertFalse(PlaybackRecoveryPolicy.needsNewAddress(java.net.SocketTimeoutException()))
        assertFalse(PlaybackRecoveryPolicy.needsNewAddress(VideoHttpException(429)))
        assertTrue(PlaybackRecoveryPolicy.needsNewAddress(java.io.IOException(VideoHttpException(403))))
        assertTrue(PlaybackRecoveryPolicy.needsNewAddress(VideoResourceChangedException()))
    }
    @Test fun modelBudgetSpansEndpointsAndRecoveryScopesButNotOtherThreads() {
        val budget=PlaybackRequestBudget(3,1)
        budget.run { repeat(2) { PlaybackRequestBudget.current.get()!!.request() };assertTrue(budget.retry()) }
        assertNull(PlaybackRequestBudget.current.get())
        budget.run { budget.request();assertFalse(budget.retry());assertThrows(PlaybackBudgetExceededException::class.java) {budget.request()} }
        assertNull(PlaybackRequestBudget.current.get())
    }
    @Test fun ordinaryDeviceReadsDoNotSerializeForegroundBehindBackground() {
        val source=PlayerDeviceSource(OkHttpClient(),store={null})
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(2)
        try {
            val background=pool.submit {source.recover {entered.countDown();release.await();"background"}}
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val foreground=pool.submit<String> {source.recover {"foreground"}}
            assertEquals("foreground",foreground.get(2,TimeUnit.SECONDS))
            release.countDown();background.get(2,TimeUnit.SECONDS)
        } finally {release.countDown();pool.shutdownNow()}
    }
}
