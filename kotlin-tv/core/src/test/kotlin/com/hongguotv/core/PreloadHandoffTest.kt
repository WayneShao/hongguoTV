package com.hongguotv.core

import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class PreloadHandoffTest {
    private class Fixture : Closeable {
        val data=ByteArray(1024*1024) {(it%239).toByte()}.also {
            ByteBuffer.wrap(it).putInt(8).put("ftyp".toByteArray()).putInt(8).put("moov".toByteArray())
        }
        val stalled=CountDownLatch(1)
        val release=CountDownLatch(1)
        val stall=AtomicBoolean(false)
        val fail=AtomicBoolean(false)
        val starts=CopyOnWriteArrayList<Int>()
        private val pool=Executors.newCachedThreadPool()
        private val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0).apply {
            executor=pool
            createContext("/video") { exchange ->
                try {
                    val parts=exchange.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-')
                    val start=parts[0].toInt(); val end=minOf(parts[1].toInt(),data.lastIndex)
                    starts+=start
                    if(start>0 && fail.compareAndSet(true,false)) {
                        exchange.sendResponseHeaders(503,-1)
                    } else {
                        exchange.responseHeaders.add("Content-Range","bytes $start-$end/${data.size}")
                        exchange.sendResponseHeaders(206,(end-start+1).toLong())
                        if(start>0 && stall.compareAndSet(true,false)) {
                            exchange.responseBody.write(data,start,1024); exchange.responseBody.flush()
                            stalled.countDown(); release.await(5,TimeUnit.SECONDS)
                            exchange.responseBody.write(data,start+1024,end-start+1-1024)
                        } else exchange.responseBody.write(data,start,end-start+1)
                    }
                } catch(_: Exception) {} finally {exchange.close()}
            }
            start()
        }
        fun video(gate: PreloadGate?=null)=RemoteVideo(OkHttpClient(),StreamInfo("http://127.0.0.1:${server.address.port}/video",null,"fixture"),gate,true)
        override fun close() {release.countDown();server.stop(0);pool.shutdownNow()}
    }

    @Test fun claimInterruptsStalledBodyAndKeepsVerifiedSharedFile() {
        Fixture().use { fixture ->
            val directory=Files.createTempDirectory("handoff").toFile()
            val executor=Executors.newSingleThreadExecutor()
            val video=fixture.video()
            try {
                fixture.stall.set(true)
                val task=PendingPreparation(executor) { claimed ->
                    video.prepare();claimed.onClaim {video.promote()}
                    video.preload(directory,claimed)
                }
                assertTrue(fixture.stalled.await(3,TimeUnit.SECONDS))
                val cached=video.cachedSize();val files=directory.listFiles()!!.toList()
                val started=System.nanoTime()
                task.claim(); assertSame(video,task.await(1000))
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1000)
                assertEquals(cached,video.cachedSize());assertTrue(files.single().exists())
                assertArrayEquals(fixture.data.copyOfRange(0,cached.toInt()),video.read(0,cached.toInt()))
                // A late demand notification must not cancel the promoted foreground reader.
                video.openRead(cached,4096).use { input ->
                    video.yieldToForeground()
                    assertArrayEquals(fixture.data.copyOfRange(cached.toInt(),cached.toInt()+4096),input.readBytes())
                }
                task.close();assertTrue(files.single().exists())
            } finally {video.close();executor.shutdownNow();directory.deleteRecursively()}
        }
    }

    @Test fun partialFailureResumesSameFileOnlyAfterCooldownAndStopsAtEpisodeTarget() {
        Fixture().use { fixture ->
            val clock=java.util.concurrent.atomic.AtomicLong(10_000);val gate=PreloadGate {clock.get()};gate.update(true,30_000,90_000)
            val directory=Files.createTempDirectory("resume").toFile()
            var loads=0;var resumes=0
            val cache=RollingPrefetch(Executor {it.run()}, {_,claimed,budget ->
                loads++;fixture.video(gate).also {video ->
                    video.prepare();claimed.onClaim {video.promote()}
                    video.preload(directory,claimed,budget) {gate.failed(it);false}
                }
            },{if(it.cachedSize()==it.total) 60_000L else null},{it.cachedSize()},{clock.get()},
                resumable={it.canResumePreload()},resumeAllowed={gate.allowed()},
                resume={video,claimed,budget ->
                    resumes++;claimed.onClaim {video.promote()}
                    // Advance the synthetic monotonic clock for the per-chunk pacing gate.
                    video.preload(directory,claimed,budget) {gate.failed(it);false}
                })
            try {
                cache.configure(PreloadSettings(enabled=true,mode=PreloadMode.EPISODES,episodes=1))
                cache.update(listOf("1","2"));fixture.fail.set(true);cache.pump()
                val file=directory.listFiles()!!.single();val size=file.length()
                repeat(3) {cache.pump()};assertEquals(0,resumes)
                clock.addAndGet(30_000);gate.update(true,30_000,90_000)
                // Clock ticks allow the existing 300 ms chunk spacing without bypassing it.
                val ticker=Executors.newSingleThreadScheduledExecutor()
                ticker.scheduleAtFixedRate({clock.addAndGet(1000)},0,10,TimeUnit.MILLISECONDS)
                try {cache.pump()} finally {ticker.shutdownNow()}
                assertEquals(1,loads);assertEquals(1,resumes)
                assertEquals(file,directory.listFiles()!!.single());assertTrue(file.length()>size)
                val video=cache.claim("1")!!.await();assertEquals(video.total,video.cachedSize())
                assertArrayEquals(fixture.data,video.read(0,fixture.data.size));video.close()
            } finally {cache.close();directory.deleteRecursively()}
        }
    }

    @Test fun claimBeforeCallbackAndCancelledQueuedResumeHaveSingleOwnership() {
        val signal=ClaimSignal();signal.claim();var calls=0
        signal.onClaim {calls++};signal.claim();assertEquals(1,calls)
        var closed=0;val owned=Closeable {closed++}
        val queue=mutableListOf<Runnable>()
        val task=PendingPreparation(Executor {queue+=it},owned) {owned}
        task.close();queue.single().run();assertEquals(1,closed)
    }

    @Test fun timedOutClaimCancelsLatePreparationAndDisposesRegistration() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val finished=CountDownLatch(1);val executor=Executors.newSingleThreadExecutor()
        val closes=java.util.concurrent.atomic.AtomicInteger()
        val callbacks=java.util.concurrent.atomic.AtomicInteger()
        try {
            val task=PendingPreparation(executor) {claim ->
                entered.countDown()
                try {release.await()} catch(_: InterruptedException) {}
                claim.onClaim {callbacks.incrementAndGet()}
                Closeable {closes.incrementAndGet()}
            }
            assertTrue(entered.await(1,TimeUnit.SECONDS));task.claim()
            assertThrows(TimeoutException::class.java) {task.await(20)}
            release.countDown();executor.submit {finished.countDown()}
            assertTrue(finished.await(1,TimeUnit.SECONDS))
            assertEquals(1,closes.get());assertEquals(0,callbacks.get())
        } finally {release.countDown();executor.shutdownNow()}
    }
}
