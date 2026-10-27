package com.hongguotv.core
import org.junit.Assert.*
import org.junit.Test
class BufferingWatchdogTest {
 @Test fun continuousBufferingTriggersOnceAtThirtySeconds() { val w=BufferingWatchdog();for(t in 0L..29) assertFalse(w.poll(t*1000,true));assertTrue(w.poll(30000,true));assertFalse(w.poll(31000,true)) }
 @Test fun pauseAndBackgroundGapsResetDeadline() { val w=BufferingWatchdog();for(t in 0L..29) w.poll(t*1000,true);assertFalse(w.poll(30000,false));assertFalse(w.poll(31000,true));assertFalse(w.poll(90000,true)) }
}
