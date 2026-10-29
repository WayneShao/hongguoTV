package com.hongguotv.core
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class NestedPlaybackModelTest {
 private val key=ByteArray(16){it.toByte()}
 private fun spade(): String {
  val plain=byteArrayOf(0)+key.hex().toByteArray();val raw=ByteArray(plain.size+1);var a=85;var b=246
  for(i in plain.indices) {val old=((plain[i].toInt() and 255)+21+Integer.bitCount(i)) xor (if(i and 1!=0) a else b);raw[i+1]=old.toByte();if(i and 1!=0)a=old and 255 else b=old and 255}
  raw[0]=(raw[1].toInt() xor raw[2].toInt()).toByte();return raw.base64()
 }
 private fun row(height: Int,encrypted: Boolean=true)=JSONObject().put("main_url","https://example.test/$height")
  .put("video_meta",JSONObject().put("vwidth",height*16/9).put("vheight",height).put("definition","${height}p").put("codec_type","h264"))
  .put("encrypt_info",JSONObject().put("encrypt",encrypted).put("spade_a",if(encrypted) spade() else ""))
 @Test fun nestedArrayRespects480CapAndRetainsTheContentKey() {
  val rows=StreamModels.arrayRows(JSONArray().put(row(1080)).put(row(480)).put(row(360)))
  val selected=StreamModels.pick(rows,480)!!
  assertEquals(480,selected.quality);assertEquals("https://example.test/480",selected.url);assertArrayEquals(key,selected.key)
 }
 @Test fun objectAndArrayFormatsUseTheSameNestedFields() {
  val selected=StreamModels.listRows(JSONObject().put("video_1",row(720)),null).single()
  assertEquals(720,selected.quality);assertArrayEquals(key,selected.key)
 }
 @Test fun encryptedRowWithMissingOrInvalidKeyIsNotPassedAsClearVideo() {
  for(value in listOf("","invalid")) {
   val row=row(480).put("encrypt_info",JSONObject().put("encrypt",true).put("spade_a",value))
   assertTrue(StreamModels.arrayRows(JSONArray().put(row)).isEmpty())
  }
 }
 @Test fun clearLegacyArrayStillWorks() {
  val row=JSONObject().put("play_url","https://example.test/clear").put("vheight",480).put("vwidth",854)
  val selected=StreamModels.arrayRows(JSONArray().put(row)).single()
  assertEquals(480,selected.quality);assertNull(selected.key)
 }
}
