package com.hongguotv.core
import java.io.Closeable
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test
class RollingPrefetchTest {
 private class Item(val seconds: Long,val size: Long=1): Closeable { var closed=false; override fun close(){closed=true} }
 private val direct=Executor { it.run() }
 @Test fun crossesFiveMinutesAtEpisodeBoundaryThenReplenishes() {
  var loads=0
  val cache=RollingPrefetch(direct,{ _,_,_ -> loads++;Item(70) },{it.seconds*1000},{it.size},{0})
  cache.update((1..20).map(Int::toString));repeat(10){cache.pump()}
  assertEquals(5,loads) // 350 seconds: do not load a sixth episode.
  cache.claim("1")!!.await().close(); cache.update((2..21).map(Int::toString));cache.pump()
  assertEquals(6,loads);assertEquals(5,cache.count());cache.close()
 }
 @Test fun shortEpisodesCanPrepareMoreThanTen() {
  val cache=RollingPrefetch(direct,{ _,_,_ -> Item(20) },{it.seconds*1000},{it.size},{0})
  cache.update((1..30).map(Int::toString));repeat(30){cache.pump()};assertEquals(15,cache.count());cache.close()
 }
 @Test fun unknownDurationAndByteLimitBoundWork() {
  var n=0;val unknown=RollingPrefetch(direct,{ _,_,_ -> n++;Item(0) },{null},{it.size},{0})
  unknown.update(listOf("1","2"));repeat(5){unknown.pump()};assertEquals(1,n);unknown.close()
  val limited=RollingPrefetch(direct,{ _,_,_ -> Item(10,5) },{it.seconds*1000},{it.size},{0},budget=10)
  limited.update((1..10).map(Int::toString));repeat(10){limited.pump()};assertEquals(2,limited.count());limited.close()
 }
 @Test fun changedQualityClosesOldResults() {
  val item=Item(400);val cache=RollingPrefetch(direct,{ _,_,_ -> item },{it.seconds*1000},{it.size},{0})
  cache.update(listOf("1:480"));cache.pump();cache.update(listOf("1:720"));assertTrue(item.closed);assertNull(cache.claim("1:480"))
 }
 @Test fun claimTransfersInFlightWorkWithoutReloading() {
  val executor=Executors.newSingleThreadExecutor();val entered=CountDownLatch(1);val release=CountDownLatch(1);var count=0
  try {
   val cache=RollingPrefetch(executor,{ _,claimed,_ -> count++;entered.countDown();release.await();assertTrue(claimed());Item(60) },{it.seconds*1000},{it.size},{0})
   cache.update(listOf("1"));cache.pump();assertTrue(entered.await(3,TimeUnit.SECONDS))
   val task=cache.claim("1")!!;cache.close();release.countDown();val item=task.await()
   assertEquals(1,count);assertFalse(item.closed);task.close();assertFalse(item.closed);item.close()
  } finally { release.countDown();executor.shutdownNow() }
 }
 @Test fun cancelBeforeLateCompletionClosesResult() {
  val executor=Executors.newSingleThreadExecutor();val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1);val item=Item(60)
  try {
   val task=PendingPreparation(executor) { entered.countDown();try { release.await() } catch(_: InterruptedException){};item }
   assertTrue(entered.await(3,TimeUnit.SECONDS));task.close();release.countDown()
   executor.submit { finished.countDown() };assertTrue(finished.await(3,TimeUnit.SECONDS));assertTrue(item.closed)
  } finally { release.countDown();executor.shutdownNow() }
 }
 @Test fun episodeModeStopsAtCountEvenWhenDurationUnknown() {
  val cache=RollingPrefetch(direct,{ _,_,_ -> Item(0) },{null},{it.size},{0})
  cache.configure(PreloadSettings(mode=PreloadMode.EPISODES,episodes=3))
  cache.update((1..20).map(Int::toString));repeat(10){cache.pump()}
  assertEquals(3,cache.count());assertFalse(cache.pump());cache.close()
 }

}
