// SPDX-License-Identifier: GPL-3.0-only
// Search registration adapted from wanwdbb/hongguoTV commit 46babe6d.
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException

internal class SearchDeviceRegistrar(private val http: OkHttpClient, private val clock: () -> Long = System::currentTimeMillis) {
    private val REGISTER_API = "https://log5-applog.fqnovel.com/service/2/device_register/"
    fun register(): PlayerDevice {
        val now=clock()
        val request=Request.Builder().url("$REGISTER_API?aid=8662&version_code=71332&channel=update_64&package=com.phoenix.read&_rticket=$now&use_store_region_cookie=1&okhttp_version=4.2.243.31-douyin")
            .header("User-Agent",VendorConstants.APP_UA)
            .header("Accept","application/json")
            .header("Accept-Language","zh-CN,zh;q=0.9")
            .header("log-encode-type","gzip")
            .header("x-ss-req-ticket",now.toString())
            .header("x-vc-bdturing-sdk-version","3.7.2.cn")
            .header("Cookie","store-region=cn-zj; store-region-src=did;")
            .post(registrationPayload().toByteArray(Charsets.UTF_8).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val raw=RequestScope.execute(http.newCall(request)) { response ->
            if(!response.isSuccessful) throw IOException("设备注册失败（HTTP ${response.code}）")
            response.body?.bytes()?.toString(Charsets.UTF_8).orEmpty()
        }
        val json = try { JSONObject(raw) } catch (e: Exception) { throw IOException("设备注册响应异常", e) }
        val deviceId=json.opt("device_id")?.toString().orEmpty()
        val installId=json.opt("install_id")?.toString().orEmpty()
        if (!deviceId.matches(Regex("[1-9][0-9]{0,24}")) || !installId.matches(Regex("[1-9][0-9]{0,24}")))
            throw IOException("设备注册被拒绝")
        return PlayerDevice(deviceId,installId,"","","")
    }
    /** 官方 `ss_app_log` 注册包的等价 JSON，字段跟 App 的查询参数保持一致。 */
    private fun registrationPayload(): String {
        val now=clock()
        val androidId=(1..8).joinToString("") { "%02x".format(kotlin.random.Random.nextInt(256)) }
        val openudid=digest("MD5",androidId.toByteArray()).hex().let { it+digest("MD5",it.toByteArray()).hex().take(8) }
        val header=JSONObject()
            .put("display_name","红果免费短剧").put("aid",8662).put("channel","update_64").put("package","com.phoenix.read")
            .put("app_version","7.1.3.32").put("version_code",71332).put("update_version_code",71332).put("manifest_version_code",71332)
            .put("app_version_minor","7.1.3.32").put("sdk_version","3.7.0-rc.25-fanqie-xiaoshuo-opt").put("sdk_target_version",29)
            .put("git_hash","5b6a0d3").put("sdk_flavor","china").put("guest_mode",0).put("is_system_app",0)
            .put("pre_installed_channel","").put("not_request_sender",0).put("os","Android")
            .put("os_version","16").put("os_api",36)
            .put("device_model","25053RT47C").put("device_brand","Redmi").put("device_manufacturer","Redmi").put("cpu_abi","arm64-v8a")
            .put("release_build","BP2A.250605.031.A3").put("density_dpi",520).put("display_density","xxxhdpi").put("resolution","1280x2772")
            .put("language","zh").put("timezone",8).put("access","wifi").put("rom","1414").put("rom_version","BP2A.250605.031.A3")
            .put("cdid",java.util.UUID.randomUUID().toString())
            .put("sig_hash",ByteArray(16).also { kotlin.random.Random.nextBytes(it) }.hex())
            .put("openudid",openudid)
            .put("clientudid",java.util.UUID.randomUUID().toString())
            .put("ipv6_list",JSONArray())
            .put("region","CN").put("tz_name","Asia/Shanghai").put("tz_offset",28800).put("sim_serial_number",JSONArray())
            .put("oaid_may_support",false).put("req_id",java.util.UUID.randomUUID().toString()).put("device_platform","android")
            .put("custom",JSONObject().put("host_bit",64).put("account_region","cn").put("dragon_device_type","phone"))
            .put("apk_first_install_time",now-2_592_000_000L)
        return JSONObject().put("magic_tag","ss_app_log").put("header",header).put("_gen_time",now).toString()
    }
}
