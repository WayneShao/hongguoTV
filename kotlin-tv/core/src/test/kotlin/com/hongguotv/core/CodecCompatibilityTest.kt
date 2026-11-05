package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodecCompatibilityTest {
    @Test fun onlyPositiveAllRowEvidenceTriggersNegotiation() {
        for(codec in listOf("bytevc2","bvc2","h265","","unknown")) {
            val model=JSONObject().put("video_list",JSONObject().put("v",JSONObject().put("codec_type",codec)))
            assertEquals(codec in listOf("bytevc2","bvc2"),StreamModels.allByteVc2(model))
            model.getJSONObject("video_list").put("unknown",JSONObject())
            assertFalse(StreamModels.allByteVc2(model))
        }
        assertFalse(StreamModels.allByteVc2(JSONObject()))
    }
    @Test fun negotiationPreservesQueryAndChecksReturnedCodecAndSharedBudget() {
        for(returned in listOf("h265","bvc2")) {
            var compatible=0
            val http=OkHttpClient.Builder().addInterceptor { chain ->
                val request=chain.request()
                val body=if(request.url.host!="example.com") {
                    """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/model?token=fixture&codec_type=4"}}}}"""
                } else {
                    assertEquals(VendorConstants.VIDEO_UA,request.header("User-Agent"))
                    val negotiating=request.url.queryParameter("force_codec")=="h264"
                    if(negotiating) { compatible++;assertEquals("0",request.url.queryParameter("codec_type"));assertEquals("fixture",request.url.queryParameter("token")) }
                    val codec=if(negotiating) returned else "bytevc2"
                    """{"video_info":{"data":{"video_list":{"v":{"vheight":480,"codec_type":"$codec","main_url":"https://example.com/video"}}}}}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
            }.build()
            val repo=ContentRepository(http)
            val budget=PlaybackRequestBudget(8,0)
            if(returned=="h265") assertEquals(480,repo.stream("123",480,budget).selectedQuality)
            else assertThrows(NoCompatibleStreamException::class.java) { repo.stream("123",480,budget) }
            assertThrows(NoCompatibleStreamException::class.java) { repo.stream("123",480,budget) }
            assertEquals(1,compatible)
        }
    }
}
