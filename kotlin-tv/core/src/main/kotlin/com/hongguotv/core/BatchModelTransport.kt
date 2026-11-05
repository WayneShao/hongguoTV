package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** One signed metadata request, never registration, media downloads or model fallback fan-out. */
class BatchModelTransport(private val http: OkHttpClient) {
    fun identity(): String? = ContentRepository.deviceStore?.load()?.takeIf { PlayerDevice.fromJson(it)!=null }
    fun fetch(identity: String,ids: List<String>,quality: Int): Map<String,StreamInfo> {
        require(ids.isNotEmpty() && ids.size<=3 && ids.distinct().size==ids.size && ids.all { it.matches(Regex("[0-9]{1,30}")) })
        if(identity()!=identity) return emptyMap()
        val device=PlayerDevice.fromJson(identity) ?: return emptyMap()
        val url=PlayerDeviceSource.substituteDevice(PlayerDeviceSource.PHOENIX_URL,device)
        val body=JSONObject(String(PlayerDeviceSource.phoenixBody(ids.first()),Charsets.UTF_8)).apply {
            getJSONObject("biz_param").put("device_level",1)
            put("mixed_video_id_map",JSONObject().put("1004",JSONArray(ids)))
        }.toString().toByteArray()
        val request=Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA)
            .header("Accept","application/json").post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        PlayerSigner.headers(url.substringAfter('?'),body).forEach { (k,v)->request.header(k,v) }
        val text=RequestScope.execute(http.newCall(request.build())) { response ->
            if(!response.isSuccessful) throw IOException("批量模型请求失败")
            val source=response.body ?: throw IOException("批量模型为空")
            if(source.contentLength()>2*1024*1024) throw IOException("批量模型过大")
            val input=source.byteStream();val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(out.size()<=2*1024*1024) { val n=input.read(buffer);if(n<0) break;out.write(buffer,0,n) }
            if(out.size()>2*1024*1024) throw IOException("批量模型过大")
            String(out.toByteArray(),Charsets.UTF_8)
        }
        val root=JSONObject(text)
        val code=(if(root.has("code")) root.opt("code") else root.opt("Code"))?.toString()?.toIntOrNull()
        if(code!=0) throw IOException("批量模型暂不可用")
        val data=root.optJSONObject("data") ?: throw IOException("批量模型结构异常")
        if(identity()!=identity) return emptyMap()
        return buildMap {
            for(id in ids) {
                val rows=StreamModels.models(data,id,false).flatMap { model ->
                    StreamModels.listRows(model.optJSONObject("video_list"),StreamModels.keySeed(model))+StreamModels.arrayRows(model.optJSONArray("video_list"))
                }
                val chosen=StreamModels.pick(rows,quality) ?: continue
                put(id,StreamInfo(chosen.url,chosen.key,if(chosen.quality>0) "${chosen.quality}P" else "自动",
                    rows.map { it.quality }.filter {it>0}.distinct().sortedDescending(),chosen.quality))
            }
        }
    }
}
