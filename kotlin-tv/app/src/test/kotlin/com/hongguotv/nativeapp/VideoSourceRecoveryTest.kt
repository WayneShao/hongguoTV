package com.hongguotv.nativeapp
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoSourceRecoveryTest {
 @Test fun reopenAfterPartialNetworkFailureKeepsExactDeliveredPositionAndCachedPrefix() {
  val bytes=ByteArray(900000){(it%239).toByte()}
  java.nio.ByteBuffer.wrap(bytes).putInt(8).put("ftyp".toByteArray()).putInt(8).put("moov".toByteArray())
  var failOnce=true;val starts=mutableListOf<Int>()
  val http=OkHttpClient.Builder().addInterceptor { chain ->
   val limits=chain.request().header("Range")!!.removePrefix("bytes=").split('-').map(String::toInt)
   val start=limits[0];val end=minOf(bytes.lastIndex,limits[1]);starts+=start
   val body=if(start>0 && failOnce) {
    failOnce=false;var delivered=0
    object: ResponseBody() {
     override fun contentType(): MediaType?=null
     override fun contentLength()=(end-start+1).toLong()
     private val raw=object: okio.Source {
      override fun timeout()=okio.Timeout.NONE
      override fun close() {}
      override fun read(sink: okio.Buffer,byteCount: Long): Long {
       if(delivered>=65536) throw java.net.SocketException("controlled disconnect")
       val n=minOf(byteCount.toInt(),65536-delivered);sink.write(bytes,start+delivered,n);delivered+=n;return n.toLong()
      }
     }.buffer()
     override fun source()=raw
    }
   } else bytes.copyOfRange(start,end+1).toResponseBody()
   Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("partial")
    .header("Content-Range","bytes $start-$end/${bytes.size}").body(body).build()
  }.build()
  RemoteVideo(http,StreamInfo("https://example.test/video",null,"480P")).prepare().use { video ->
   val source=VideoDataSource(video);val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);var failures=0
   fun reopen() {source.open(DataSpec.Builder().setUri("hongguotv://episode/test").setPosition(out.size().toLong()).build())}
   try {
    reopen()
    while(out.size()<bytes.size) {
     try {val n=source.read(buffer,0,buffer.size);if(n<0) break;out.write(buffer,0,n)}
     catch(e: java.net.SocketException) {failures++;source.close();reopen()}
    }
    assertEquals(1,failures);assertArrayEquals(bytes,out.toByteArray());assertTrue(video.reusable)
    assertEquals(listOf(0,32768,98304,622592),starts)
   } finally {source.close()}
  }
 }
}
