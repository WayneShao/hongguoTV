package com.hongguotv.core
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.After
import org.junit.Assert.*
class BatchModelTransportTest {
 @After fun clear(){ContentRepository.deviceStore=null}
 @Test fun exactPartialBatchAndIdentityChangeNeverLeakOtherEpisodes() {
  var identity=PlayerDevice("123","456","c","o","").toJson()
  ContentRepository.deviceStore=object: PlayerDeviceStore {override fun load()=identity;override fun save(value:String?){error("no registration or persistence")}}
  var calls=0
  val http=OkHttpClient.Builder().addInterceptor { chain ->
   calls++;val request=chain.request();assertTrue(request.url.encodedPath.endsWith("multi_video_model/v1/"))
   val b=okio.Buffer();request.body!!.writeTo(b);val json=org.json.JSONObject(b.readUtf8())
   assertEquals(1,json.getJSONObject("biz_param").getInt("device_level"))
   assertEquals(listOf("1","2"),(0..1).map{json.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(it)})
   Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("""{"code":0,"data":{"1":{"video_model":{"video_list":[{"main_url":"https://example.com/1","vheight":480,"codec_type":"h264"}]}},"999":{"video_model":{"video_list":[{"main_url":"https://example.com/wrong","vheight":480,"codec_type":"h264"}]}}}}""".toResponseBody()).build()
  }.build()
  val transport=BatchModelTransport(http);val old=identity
  assertEquals(setOf("1"),transport.fetch(old,listOf("1","2"),480).keys)
  identity=PlayerDevice("789","456","c","o","").toJson()
  assertTrue(transport.fetch(old,listOf("1","2"),480).isEmpty());assertEquals(1,calls)
 }
}
