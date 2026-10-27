package com.hongguotv.core
import org.junit.Assert.*
import org.junit.Test
class PreloadSettingsTest {
 @Test fun independentDefaultsAndInvalidValueRecovery() {
  val c=PreloadSettings(minutes=-1,episodes=999,checkMinutes=0,checkEpisodes=999).normalized()
  assertEquals(5,c.minutes);assertEquals(10,c.episodes);assertEquals(1,c.checkMinutes);assertEquals(1,c.checkEpisodes)
  assertEquals(5,c.copy(mode=PreloadMode.EPISODES).minutes)
 }
 @Test fun timeModeCountsActualPlaybackAndDoesNotCheckEveryEpisode() {
  val clock=PreloadCheckClock();val c=PreloadSettings()
  assertTrue(clock.due(c));clock.checked()
  for(i in 0L..59) clock.sample(i*1000,true)
  clock.episodeCompleted();assertFalse(clock.due(c))
  clock.sample(60000,true);assertTrue(clock.due(c));clock.checked();assertFalse(clock.due(c))
 }
 @Test fun episodeModeIgnoresTimeAndWaitsForConfiguredCompletions() {
  val clock=PreloadCheckClock();val c=PreloadSettings(mode=PreloadMode.EPISODES,checkEpisodes=3)
  clock.checked();for(i in 0L..300) clock.sample(i*1000,true)
  assertFalse(clock.due(c));repeat(2){clock.episodeCompleted()};assertFalse(clock.due(c))
  clock.episodeCompleted();assertTrue(clock.due(c))
 }
 @Test fun pauseAndLargeClockGapsDoNotCountAsPlayback() {
  val clock=PreloadCheckClock();clock.checked();clock.sample(0,true);clock.sample(120000,true)
  clock.sample(121000,false);clock.sample(240000,true);assertFalse(clock.due(PreloadSettings()))
 }
}
