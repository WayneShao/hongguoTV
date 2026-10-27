package com.hongguotv.core
import org.junit.Assert.*
import org.junit.Test
class PlaybackReadRetryTest {
 @Test fun emptyReplyRetriesWithBoundedBackoff() {
  val delays=mutableListOf<Long>(); var n=0
  assertEquals("ok",PlaybackReadRetry { delays+=it }.read { if(++n<3) "" else "ok" })
  assertEquals(listOf(700L,1500L),delays)
 }
 @Test fun permanentErrorsAndMalformedResponsesDoNotRetry() {
  for(code in listOf(400,401,403,404)) { var n=0
   assertThrows(PlaybackHttpException::class.java) { PlaybackReadRetry { error("no retry") }.read { n++; throw PlaybackHttpException(code) } }
   assertEquals(1,n)
  }
  assertEquals("{}",PlaybackReadRetry { error("no retry") }.read { "{}" })
 }
 @Test fun transientHttpFailureRetriesOnlyOnce() {
  var n=0; assertThrows(PlaybackHttpException::class.java) { PlaybackReadRetry {}.read { n++; throw PlaybackHttpException(503) } };assertEquals(2,n)
 }
 @Test fun cancellationStopsBeforeSecondRead() {
  val scope=RequestScope();var n=0
  assertThrows(java.io.IOException::class.java) { scope.run { PlaybackReadRetry { scope.cancel() }.read { n++; "" } } };assertEquals(1,n)
 }
}
