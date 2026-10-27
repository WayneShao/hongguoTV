package com.hongguotv.core
import org.junit.Assert.*
import org.junit.Test
class Mp4DurationTest {
 private fun fixture(version: Int,scale: Int,length: Long): ByteArray {
  val payload=java.nio.ByteBuffer.allocate(if(version==1) 32 else 20)
  payload.putInt(version shl 24)
  if(version==1) payload.putLong(0).putLong(0).putInt(scale).putLong(length) else payload.putInt(0).putInt(0).putInt(scale).putInt(length.toInt())
  return java.nio.ByteBuffer.allocate(16+payload.capacity()).putInt(16+payload.capacity()).put("moov".toByteArray()).putInt(8+payload.capacity()).put("mvhd".toByteArray()).put(payload.array()).array()
 }
 @Test fun readsMovieTimescaleForBothVersions() { for(v in 0..1) assertEquals(65_000L,Mp4Duration.read(fixture(v,1000,65000))) }
 @Test fun corruptOrUnknownDurationIsNotGuessed() { assertNull(Mp4Duration.read(fixture(0,0,12)));assertNull(Mp4Duration.read(byteArrayOf(1,2))) }
}
