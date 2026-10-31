package com.hongguotv.core

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.Executor
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CompletedPreloadTest {
 private class Item(val size: Long=5, var complete: Boolean=true): Closeable {
  var closed=false
  override fun close() { closed=true }
 }
 private val direct=Executor { it.run() }
 @Test fun completedProbeNeverClaimsAnInFlightPreparation() {
  var pending: Runnable?=null
  var claims=0
  val window=RollingPrefetch(Executor {pending=it},{_,signal,_->signal.onClaim {claims++};Item()},
   {70_000L},{it.size},{0},completed={it.complete})
  window.update(listOf("1"));window.pump()
  assertNull(window.claimCompleted("1"));assertEquals(0,claims)
  pending!!.run();val result=window.claimCompleted("1")!!
  assertEquals(1,claims);window.close();assertFalse(result.closed)
  window.releasePinned("1",result);result.close()
 }
 @Test fun pinnedCompleteItemCountsTowardHorizonAndCannotBeDownloadedTwice() {
  var loads=0
  val window=RollingPrefetch(direct,{_,_,_->loads++;Item()}, {70_000L},{it.size},{0},completed={it.complete})
  window.update((1..10).map(Int::toString));repeat(10){window.pump()}
  val queued=window.claimCompleted("1")!!
  assertNull(window.claimCompleted("1"));assertNull(window.claim("1"))
  repeat(10){window.pump()}
  assertEquals(5,loads);assertEquals(5,window.count());assertEquals(25L,window.cachedBytes())
  window.consumed("1");window.update((2..11).map(Int::toString));window.pump()
  assertEquals(6,loads);assertFalse(queued.closed)
  window.close();queued.close()
 }
 @Test fun pinSurvivesDisableCloseAndDesiredChangeWithoutResourceCloseOrBudgetEscape() {
  var loads=0
  val window=RollingPrefetch(direct,{_,_,_->loads++;Item()}, {70_000L},{it.size},{0},budget=5,completed={it.complete})
  window.update(listOf("1"));window.pump();val queued=window.claimCompleted("1")!!
  window.configure(PreloadSettings(enabled=false));window.close()
  assertFalse(queued.closed);assertEquals(5L,window.cachedBytes())
  window.configure(PreloadSettings(enabled=true));window.update(listOf("2"));window.pump()
  assertEquals(1,loads)
  window.consumed("1");window.pump();assertEquals(2,loads)
  queued.close();window.close()
 }
 @Test fun incompleteClaimDoesNotTransferOrNotifyAndMemoryStillIncludesPinnedItems() {
  var claims=0
  val item=Item(1,false)
  val window=RollingPrefetch(direct,{_,signal,_->signal.onClaim {claims++};item}, {70_000L},{it.size},{0},
   memory={10L},memoryBudget=10,completed={it.complete})
  window.update(listOf("1","2"));window.pump()
  assertNull(window.claimCompleted("1"));assertEquals(0,claims)
  item.complete=true;assertSame(item,window.claimCompleted("1"));assertEquals(1,claims)
  assertEquals(10L,window.retainedBytes());assertFalse(window.pump());window.close();assertFalse(item.closed)
  window.consumed("1");item.close()
 }
 @Test fun cacheOnlyRejectsMissingOrTruncatedFileWithoutNetworkFallback() {
  val media=ByteBuffer.allocate(16).putInt(8).put("ftyp".toByteArray()).putInt(8).put("moov".toByteArray()).array()+ByteArray(80_000){(it%239).toByte()}
  var requests=0
  val http=OkHttpClient.Builder().addInterceptor {chain ->
   requests++
   val range=chain.request().header("Range")!!.removePrefix("bytes=").split('-').map(String::toInt)
   val end=minOf(range[1],media.lastIndex)
   Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("partial")
    .header("Content-Range","bytes ${range[0]}-$end/${media.size}").body(media.copyOfRange(range[0],end+1).toResponseBody()).build()
  }.build()
  val dir=Files.createTempDirectory("complete-cache").toFile()
  val video=RemoteVideo(http,StreamInfo("https://example.test/video",null,"480P"))
  try {
   video.prepare();assertFalse(video.isCacheComplete())
   video.preload(dir,{false});assertTrue(video.isCacheComplete())
   val before=requests;assertArrayEquals(media,video.readCachedOnly(0,media.size));assertEquals(before,requests)
   val file=dir.listFiles()!!.single()
   java.io.RandomAccessFile(file,"rw").use {it.setLength(10)}
   assertFalse(video.isCacheComplete())
   try {video.readCachedOnly(0,8);fail("truncated cache accepted")} catch(_: IOException) {}
   file.delete()
   try {video.readCachedOnly(0,8);fail("missing cache accepted")} catch(_: IOException) {}
   assertEquals(before,requests)
  } finally {video.close();dir.deleteRecursively();http.connectionPool.evictAll();http.dispatcher.executorService.shutdownNow()}
 }
 @Test fun delayedReleaseCannotUnpinReplacementWithSameKey() {
  val window=RollingPrefetch(direct,{_,_,_->Item()}, {70_000L},{it.size},{0},completed={it.complete})
  window.update(listOf("1"));window.pump();val first=window.claimCompleted("1")!!
  window.consumed("1",first);window.pump();val replacement=window.claimCompleted("1")!!
  window.releasePinned("1",first);assertEquals(1,window.pinnedCount())
  window.consumed("1",first);assertEquals(1,window.pinnedCount())
  window.releasePinned("1",replacement);assertEquals(0,window.pinnedCount())
  first.close();replacement.close();window.close()
 }
}
