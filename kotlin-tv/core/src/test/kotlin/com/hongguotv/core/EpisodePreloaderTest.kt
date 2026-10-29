package com.hongguotv.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Exercises the same coordinator used by the Activity, including real HTTP and disk bytes. */
class EpisodePreloaderTest {
 private val media=ByteBuffer.allocate(44).putInt(8).put("ftyp".toByteArray()).putInt(36).put("moov".toByteArray())
  .putInt(28).put("mvhd".toByteArray()).putInt(0).putInt(0).putInt(0).putInt(1000).putInt(70000).array()+ByteArray(80000){(it%239).toByte()}
 private fun scenario(action: (EpisodePreloader,AtomicInteger,AtomicInteger,AtomicLong,PreloadGate,()->Unit)->Unit) {
  val requests=AtomicInteger();val resolutions=AtomicInteger();val fail=AtomicInteger()
  val clock=AtomicLong(10000)
  // Virtual scheduling clock; HTTP itself uses actual sockets and actual cancellation.
  val gate=PreloadGate { clock.getAndAdd(1000) }
  val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
  val network=Executors.newCachedThreadPool();server.executor=network
  server.createContext("/video") { e ->
   try {
    requests.incrementAndGet()
    val range=e.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-').map(String::toInt)
    val start=range[0];val end=minOf(media.lastIndex,range[1])
    if(start>0 && fail.getAndSet(0)==1) {e.sendResponseHeaders(503,-1)} else {
     e.responseHeaders.add("Content-Range","bytes $start-$end/${media.size}")
     e.sendResponseHeaders(206,(end-start+1).toLong());e.responseBody.use {it.write(media,start,end-start+1)}
    }
   } finally {e.close()}
  }
  server.start();val worker=Executors.newSingleThreadExecutor();val dir=Files.createTempDirectory("episode-coordinator").toFile()
  val http=OkHttpClient.Builder().readTimeout(1,TimeUnit.SECONDS).build()
  val loader=EpisodePreloader(worker,http,{dir},{ resolutions.incrementAndGet();StreamInfo("http://127.0.0.1:${server.address.port}/video",null,"480P") },gate,{clock.get()})
  gate.update(true,30000,70000)
  try {action(loader,requests,resolutions,clock,gate,{fail.set(1)})}
  finally {loader.close();worker.shutdownNow();server.stop(0);network.shutdownNow();http.connectionPool.evictAll();http.dispatcher.executorService.shutdownNow();dir.deleteRecursively()}
 }
 private fun fill(loader: EpisodePreloader) {
  val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
  while(loader.pump()) {if(System.nanoTime()>end) fail("preload never finished");Thread.sleep(10)}
 }
 @Test fun enabledTimeWindowDownloadsFiveCompleteEpisodesThenTransfersWithoutNetwork()=scenario { loader,requests,resolutions,_,_,_ ->
  loader.configure(PreloadSettings(minutes=5,enabled=true));loader.update((1..10).map(Int::toString));fill(loader)
  assertEquals(5,resolutions.get());assertEquals(5,loader.count());assertEquals(10,requests.get())
  val task=loader.claim("1")!!;val video=task.await(500);task.close()
  val before=requests.get();assertEquals(media.size.toLong(),video.cachedSize());assertArrayEquals(media,video.read(0,media.size))
  assertEquals(before,requests.get());loader.close();assertTrue(video.reusable);video.close()
 }
 @Test fun disabledCoordinatorMakesNoModelOrVideoRequest()=scenario { loader,requests,resolutions,_,_,_ ->
  loader.configure(PreloadSettings(enabled=false));loader.update(listOf("1","2"));assertFalse(loader.pump())
  assertEquals(0,requests.get());assertEquals(0,resolutions.get())
 }
 @Test fun partialFailureResumesSameEpisodeBeforeExpandingWindow()=scenario { loader,requests,resolutions,clock,gate,failNext ->
  loader.configure(PreloadSettings(mode=PreloadMode.EPISODES,episodes=2,enabled=true));loader.update(listOf("1","2"));failNext()
  loader.pump()
  val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
  while(requests.get()<2) {if(System.nanoTime()>end) fail("missing failed chunk");Thread.sleep(10)}
  Thread.sleep(50)
  assertEquals(1,resolutions.get());assertFalse(gate.allowed())
  clock.addAndGet(31000);gate.update(true,30000,70000);fill(loader)
  assertEquals(2,resolutions.get());assertEquals(5,requests.get()) // failed range repeats; model and prefix do not.
  val video=loader.claim("1")!!.await(500)
  try {assertArrayEquals(media,video.read(0,media.size))} finally {video.close()}
 }
}
