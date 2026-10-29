package com.hongguotv.core
import org.junit.Assert.*
import org.junit.Test
class PreloadGateTest {
 @Test fun lowBufferAndPauseInterruptActiveWorkOutsideGateLock() {
  val gate=PreloadGate {0};var interruptions=0
  gate.onDemand={assertFalse(Thread.holdsLock(gate));interruptions++}
  gate.update(true,25000,60000)
  gate.update(true,7000,60000);assertEquals(1,interruptions)
  gate.update(true,7000,60000);assertEquals(1,interruptions)
  gate.update(true,25000,60000)
  gate.update(false,25000,60000);assertEquals(2,interruptions)
  gate.update(false,0,60000);assertEquals(2,interruptions)
 }
 @Test fun foregroundDemandAndLowWatermarkAlwaysWin() {
  var now=0L;val gate=PreloadGate { now }
  gate.update(true,25000,60000);assertTrue(gate.allowed())
  gate.networkStarted();assertFalse(gate.allowed());gate.networkFinished()
  now=3999;assertFalse(gate.allowed());now=4000;assertTrue(gate.allowed())
  gate.update(true,7000,60000);assertFalse(gate.allowed())
  gate.update(true,15000,60000);assertFalse(gate.allowed())
  gate.update(true,21000,60000);assertTrue(gate.allowed())
 }
 @Test fun cooldownDoesNotDisappearOnRecoveryOrBufferGrowth() {
  var now=0L;val gate=PreloadGate { now };gate.update(true,25000,60000)
  gate.failed(VideoHttpException(429));gate.update(true,25000,60000);now=119999;assertFalse(gate.allowed())
  now=120000;assertTrue(gate.allowed())
  gate.failed(java.io.IOException("offline"));gate.update(true,25000,60000);now+=29999;assertFalse(gate.allowed());now++;assertTrue(gate.allowed())
 }
 @Test fun chunkSpacingAndShortFinalEpisode() {
  var now=0L;val gate=PreloadGate { now };gate.update(true,3000,3000);assertTrue(gate.allowed())
  gate.chunkFinished();assertFalse(gate.allowed());now=300;assertTrue(gate.allowed())
 }
}
