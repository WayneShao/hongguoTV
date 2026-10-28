package com.hongguotv.core
import java.nio.ByteBuffer
import java.nio.file.Files
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
class DiskPreloadTest {
 private fun fixture(): ByteArray {
  val header=ByteBuffer.allocate(44).putInt(8).put("ftyp".toByteArray()).putInt(36).put("moov".toByteArray())
    .putInt(28).put("mvhd".toByteArray()).putInt(0).putInt(0).putInt(0).putInt(1000).putInt(45000).array()
  return header+ByteArray(700000-header.size){(it%239).toByte()}
 }
 @Test fun fullyCachedEpisodeReadsWithoutNetworkAndDeletesOnClose() {
  val data=fixture();var requests=0
  val http=OkHttpClient.Builder().addInterceptor { chain ->
   requests++;val parts=chain.request().header("Range")!!.removePrefix("bytes=").split('-').map(String::toInt)
   val end=minOf(parts[1],data.lastIndex)
   Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("partial")
    .header("Content-Range","bytes ${parts[0]}-$end/${data.size}").body(data.copyOfRange(parts[0],end+1).toResponseBody()).build()
  }.build()
  val dir=Files.createTempDirectory("preload-test").toFile()
  val video=RemoteVideo(http,StreamInfo("https://example.test/video",null,"480P"))
  try {
   video.preload(dir,{false});assertEquals(45000L,video.durationMs());assertEquals(data.size.toLong(),video.cachedSize())
   assertEquals(1,dir.listFiles()!!.size);val before=requests
   assertArrayEquals(data.copyOfRange(40000,500000),video.read(40000,460000));assertEquals(before,requests)
   video.close();assertTrue(dir.listFiles()!!.isEmpty())
  } finally {video.close();dir.deleteRecursively()}
 }
 @Test fun failedBackgroundChunkKeepsVerifiedPrefixAndResumesAtSameOffset() {
  val data=fixture();val offsets=mutableListOf<Int>();var fail=true
  val http=OkHttpClient.Builder().addInterceptor { chain ->
   val parts=chain.request().header("Range")!!.removePrefix("bytes=").split('-').map(String::toInt)
   offsets+=parts[0]
   if(parts[0]>=294912 && fail) { fail=false;throw java.net.SocketTimeoutException("stall") }
   val end=minOf(parts[1],data.lastIndex)
   Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("partial")
    .header("Content-Range","bytes ${parts[0]}-$end/${data.size}").body(data.copyOfRange(parts[0],end+1).toResponseBody()).build()
  }.build()
  val dir=Files.createTempDirectory("preload-retry").toFile()
  try { RemoteVideo(http,StreamInfo("https://example.test/video",null,"480P")).use { video ->
   video.preload(dir,{false})
   assertEquals(294912L,video.cachedSize());assertTrue(video.reusable)
   val file=dir.listFiles()!!.single()
   video.preload(dir,{false})
   assertEquals(file,dir.listFiles()!!.single());assertEquals(data.size.toLong(),video.cachedSize())
   assertEquals(listOf(0,32768,294912,294912,557056),offsets)
   assertArrayEquals(data.copyOfRange(0,700000),video.read(0,700000))
  } } finally {dir.deleteRecursively()}
 }
 @Test fun claimingDuringPreloadTransfersPartialCache() {
  val data=fixture();var calls=0
  val http=OkHttpClient.Builder().addInterceptor { chain ->
   calls++;val parts=chain.request().header("Range")!!.removePrefix("bytes=").split('-').map(String::toInt);val end=minOf(parts[1],data.lastIndex)
   Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("partial")
    .header("Content-Range","bytes ${parts[0]}-$end/${data.size}").body(data.copyOfRange(parts[0],end+1).toResponseBody()).build()
  }.build()
  val dir=Files.createTempDirectory("preload-claim").toFile()
  RemoteVideo(http,StreamInfo("https://example.test/video",null,"480P")).use { video ->
   video.preload(dir,{calls>=2});assertTrue(video.cachedSize()<data.size);assertEquals(2,calls)
   assertArrayEquals(data.copyOfRange(400000,400100),video.read(400000,100))
  };assertTrue(dir.listFiles()!!.isEmpty());dir.delete()
 }
}
