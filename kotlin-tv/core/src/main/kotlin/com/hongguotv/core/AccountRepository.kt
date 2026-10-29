// SPDX-License-Identifier: GPL-3.0-only
// Account protocol adapted from wanwdbb/hongguoTV d5e4c5f800.
package com.hongguotv.core

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.URI

internal fun cleanText(s: String) = s.filter { it.code>=32 }.replace(Regex("[\\s\\u00a0\\u3000]+")," ").trim()
private const val QR_CREATE_API = "https://hongguoduanju.com/passport/web/get_qrcode/"
private const val QR_CHECK_API = "https://hongguoduanju.com/passport/web/check_qrconnect/"
private const val QR_WEB = "https://hongguoduanju.com/"
private const val QR_AID = "513755"
private const val QR_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
private const val USER_INFO_API = "https://api5-normal-sinfonlineb.fqnovel.com/reading/user/info/v"
private const val HISTORY_API = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/read_history/list/v"
private const val FAVORITES_API = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/bookshelf/video/list/v"
private const val RESERVATIONS_API = "https://api5-normal-sinfonlineb.fqnovel.com/reading/user/subscribe/list/v1/"
private const val RESERVATION_API = "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/search/uncover_subscribe/v:version/"
/** 扫码登录二维码；只把图片和过期时间交给界面，二维码 token 留在 repository 内。 */
data class LoginQr(val image: String, val expiresAt: Long) {
    override fun toString() = "LoginQr(expiresAt=$expiresAt,image=${image.length}chars)"
}
enum class LoginQrStatus { NEW, SCANNED, CONFIRMED, EXPIRED }
data class LoginQrPoll(val status: LoginQrStatus, val session: AuthSession? = null) {
    override fun toString() = "LoginQrPoll(status=$status,session=${if(session==null) "none" else "***"})"
}
/** 红果账号资料；`mobile` 始终是客户端脱敏后的号码。 */
data class AuthUser(
    val id: String,
    val name: String,
    val avatar: String = "",
    val mobile: String = "",
    val description: String = "",
    val followers: Long = 0,
    val following: Long = 0,
    val newUser: Boolean = false,
    /** 红果账号的数字用户 ID；只用于收藏接口，不展示、不写日志。 */
    val accountId: String = ""
) {
    fun toJson() = JSONObject().put("id",id).put("name",name).put("avatar",avatar).put("mobile",mobile)
        .put("description",description).put("followers",followers).put("following",following).put("newUser",newUser)
        .put("accountId",accountId)
    companion object {
        fun fromJson(o: JSONObject): AuthUser? {
            val id=o.str("id"); val name=o.str("name")
            if(id.isBlank() && name.isBlank()) return null
            return AuthUser(id,name,cleanUrl(o.str("avatar")),o.str("mobile"),o.str("description"),
                o.optLong("followers",0),o.optLong("following",0),o.optBoolean("newUser",false),o.str("accountId"))
        }
    }
}
/** 账号观看记录；`episodeId` / `episodeIndex` 用于从账号进度继续播放。 */
data class AccountHistoryItem(
    val series: Series,
    val episodeId: String = "",
    val episodeIndex: Int = 0,
    val position: Long = 0,
    val duration: Long = 0,
    val readAt: Long = 0
)
/** 账号预约记录；`itemId` 同时用于取消预约，`online` 为 false 时没有可播放剧集。 */
data class AccountReservation(
    val series: Series,
    val itemType: Int = 14,
    val online: Boolean = false,
    val expectedAt: Long = 0,
    val subscribed: Boolean = true,
    val expectedText: String = ""
)
/** 账号记录页的统一返回，避免三个列表页各自维护一套类型。 */
data class AccountPage(
    val items: List<Series>,
    val history: List<AccountHistoryItem> = emptyList(),
    val reservations: List<AccountReservation> = emptyList()
)
/** 登录会话：服务端下发的 Cookie 与 X-Tt-Token；只保存在应用私有存储，不写日志。 */
data class AuthSession(val cookies: Map<String,String>, val token: String, val user: AuthUser) {
    fun toJson() = JSONObject().put("cookies",JSONObject().also { target -> cookies.forEach { (k,v) -> target.put(k,v) } })
        .put("token",token).put("user",user.toJson())
    override fun toString() = "AuthSession(user=${user.name},cookies=${cookies.size},token=${if(token.isBlank()) "none" else "***"})"
    companion object {
        fun fromJson(o: JSONObject): AuthSession? {
            val user=o.optJSONObject("user")?.let(AuthUser::fromJson) ?: return null
            val cookies=o.optJSONObject("cookies")?.let { target ->
                target.keys().asSequence().associateWith { target.optString(it,"") }.filterValues { it.isNotBlank() }
            }.orEmpty()
            return AuthSession(cookies,o.str("token"),user)
        }
    }
}

class AccountRepository(val content: ContentRepository = ContentRepository(), private val clock: () -> Long = System::currentTimeMillis) {
    constructor(http: OkHttpClient, clock: () -> Long = System::currentTimeMillis): this(ContentRepository(http),clock)
    val http = content.http.newBuilder().connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(12,java.util.concurrent.TimeUnit.SECONDS).callTimeout(15,java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val signer=Signer()
    private val site="https://hongguoduanju.com"
    fun detail(id: String)=content.detail(id)
    private val authLock=Any()
    private val authCookies=linkedMapOf<String,String>()
    @Volatile private var authToken=""
    @Volatile private var authUser: AuthUser?=null
    /** 扫码登录的一次性会话；二维码 token 不离开 repository，也不进入日志。 */
    private val qrLock=Any()
    private var qrGeneration=0
    private var qrSession: QrSession?=null
    private class QrSession(val client: OkHttpClient,val store: QrCookieStore,val qrToken: String) {
        @Volatile var appToken=""
        override fun toString() = "QrSession(appToken=${if(appToken.isBlank()) "none" else "***"})"
    }
    /** 手动维护扫码会话 Cookie，避免依赖 OkHttp 的全局 CookieJar，也便于单测检查请求头。 */
    private class QrCookieStore {
        private val cookies=mutableListOf<Cookie>()
        @Synchronized fun save(response: Response) {
            val url=response.request.url
            response.headers("Set-Cookie").forEach { raw ->
                val cookie=runCatching { Cookie.parse(url,raw) }.getOrNull() ?: return@forEach
                cookies.removeAll { it.name==cookie.name && it.domain==cookie.domain && it.path==cookie.path }
                if(cookie.expiresAt>System.currentTimeMillis()) cookies.add(cookie)
            }
        }
        @Synchronized fun header(url: HttpUrl): String = cookies
            .filter { it.expiresAt>System.currentTimeMillis() && it.matches(url) }
            .joinToString("; ") { "${it.name}=${it.value}" }
        @Synchronized fun snapshot(): Map<String,String> = cookies
            .filter { it.expiresAt>System.currentTimeMillis() }
            .associate { it.name to it.value }
    }
    /** 官方接口统一使用较新的版本号；网站接口仍沿用 [VendorConstants.device]。 */
    private fun identityValues()=VendorConstants.device.toMutableMap().also { values ->
        ContentRepository.searchDeviceStore?.load()?.let(PlayerDevice::fromJson)?.let {
            values["device_id"]=it.deviceId; values["iid"]=it.installId
        }
    }
    private fun officialValues() = identityValues().also {
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) it[k]="72232"
        it["version_name"]="7.2.2.32"
    }
    /** 作者作品、相关作品等新版接口按版本号开量；旧版本会返回空数据，不能复用 [officialValues]。 */
    private fun recentOfficialValues() = identityValues().also {
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) it[k]="73932"
        it["version_name"]="7.3.9.32"
    }
    private fun responseText(response: Response): String {
        val body = response.body ?: throw IOException("内容响应为空")
        if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
        val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
        while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
        if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
        return String(bytes,Charsets.UTF_8)
    }
    private fun authRequestHeaders(): Map<String,String> {
        val snapshot=synchronized(authLock) { if(authUser==null) null else cookieHeader(authCookies) to authToken }
        if(snapshot==null) return emptyMap()
        val (cookie,token)=snapshot
        return buildMap {
            if(cookie.isNotBlank()) put("Cookie",cookie)
            if(token.isNotBlank()) put("X-Tt-Token",token)
        }
    }
    private fun isOfficialHost(url: String) = runCatching {
        val host=URI(url).host?.lowercase() ?: return@runCatching false
        host=="fqnovel.com" || host.endsWith(".fqnovel.com")
    }.getOrDefault(false)
    private fun text(url: String, signed: Signer.Request? = null, headers: Map<String,String> = emptyMap()): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        if(isOfficialHost(url)) authRequestHeaders().forEach { (k,v) -> request.header(k,v) }
        headers.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            responseText(response)
        }
    }
    fun restoreSession(session: AuthSession?) {
        synchronized(authLock) {
            authCookies.clear()
            if(session==null) {
                authToken=""; authUser=null
            } else {
                authCookies.putAll(session.cookies)
                authToken=session.token; authUser=session.user
            }
        }
    }
    fun currentSession(): AuthSession? = synchronized(authLock) {
        authUser?.let { AuthSession(authCookies.toMap(),authToken,it) }
    }
    fun logout() { cancelLoginQr(); restoreSession(null) }
    /**
     * 获取红果网页扫码登录二维码。网页接口只返回 PNG data URI 和一次性 token；
     * token 留给 [pollLoginQr] 使用，不返回给界面。
     */
    fun createLoginQr(): LoginQr {
        val client=http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val store=QrCookieStore()
        val generation=synchronized(qrLock) {
            qrGeneration++
            qrSession=null
            qrGeneration
        }
        val (root,appToken)=qrJson(client,store,qrUrl(QR_CREATE_API,""),"")
        val data=root.optJSONObject("data") ?: throw IOException(qrError(root))
        val token=data.str("token")
        val image=data.str("qrcode")
        if(token.isBlank() || image.isBlank()) throw IOException(qrError(root))
        val expireSeconds=data.optLong("expire_time",0)
        val expiresAt=if(expireSeconds>0) expireSeconds*1000 else clock()+180_000
        val imageData=qrImage(image)
        val session=QrSession(client,store,token).also { it.appToken=appToken }
        synchronized(qrLock) {
            if(qrGeneration!=generation) throw IOException("二维码已失效，请刷新")
            qrSession=session
        }
        return LoginQr(imageData,expiresAt)
    }
    /** 轮询扫码状态；确认后跟随官方 redirect_url，收集 Cookie 并建立登录会话。 */
    fun pollLoginQr(): LoginQrPoll {
        val session=synchronized(qrLock) { qrSession } ?: throw IOException("二维码已失效，请刷新")
        val (root,headerToken)=qrJson(session.client,session.store,qrUrl(QR_CHECK_API,session.qrToken),session.appToken)
        if(headerToken.isNotBlank()) session.appToken=headerToken
        val data=root.optJSONObject("data") ?: throw IOException(qrError(root))
        val extra=data.optJSONObject("extra")
        val redirect=cleanUrl(data.str("redirect_url").ifEmpty { data.str("redirectUrl") }
            .ifEmpty { extra?.str("redirect_url").orEmpty() })
        val status=qrStatus(data.str("status"),redirect.isNotBlank())
            ?: throw IOException(qrError(root).takeIf { it!="二维码服务暂时不可用" } ?: "二维码状态异常，请刷新重试")
        if(status==LoginQrStatus.CONFIRMED) {
            var token=session.appToken
            if(redirect.isNotBlank()) token=runCatching { followQrRedirects(session.client,session.store,redirect,token) }.getOrDefault(token)
            // 落地页可能再补一次 Cookie；失败不覆盖已拿到的登录态。
            token=runCatching { followQrRedirects(session.client,session.store,QR_WEB,token) }.getOrDefault(token)
            val cookies=session.store.snapshot()
            if(!hasLoginCookie(cookies)) throw IOException("登录状态未返回，请刷新二维码重试")
            if(synchronized(qrLock) { qrSession!==session }) throw IOException("二维码已失效，请刷新")
            val auth=synchronized(qrLock) {
                if(qrSession!==session) throw IOException("二维码已失效，请刷新")
                // Install session only while this QR still belongs to the active window.
                val id=qrUserId(cookies)
                restoreSession(AuthSession(cookies,token,AuthUser(id,"")))
                qrSession=null
                currentSession() ?: throw IOException("登录状态保存失败")
            }
            val resolved=runCatching { refreshUser() }.getOrElse {
                if(authExpired(it)) { restoreSession(null); throw it }; auth
            }
            return LoginQrPoll(LoginQrStatus.CONFIRMED,resolved)
        }
        if(status==LoginQrStatus.EXPIRED) {
            synchronized(qrLock) { if(qrSession===session) qrSession=null }
            return LoginQrPoll(LoginQrStatus.EXPIRED)
        }
        return LoginQrPoll(status)
    }
    fun cancelLoginQr() = synchronized(qrLock) { qrGeneration++; qrSession=null }
    /** 用已登录会话刷新昵称/头像；失败时保留原资料，由界面提示重新登录。 */
    fun refreshUser(): AuthSession {
        val current=currentSession() ?: throw IOException("请先登录")
        val signed=signer.sign(USER_INFO_API,recentOfficialValues().also {
            it["check_idfa"]="false"; it["not_need_perm"]="false"; it["is_bind_douyin"]="false"
            it["enable_preference_v709"]="false"; it["code"]="0"; it["need_perm_from_master"]="false"
        })
        val root=runCatching { JSONObject(text(signed.url,signed,mapOf("Cookie" to cookieHeader(current.cookies),"X-Tt-Token" to current.token))) }.getOrElse { throw IOException("账号资料返回异常") }
        if(root.optInt("code",-1)!=0) throw IOException(authError(root))
        val data=root.optJSONObject("data") ?: throw IOException("账号资料为空")
        ensureHistorySession(current)
        val accountId=listOf(data.str("req_id"),data.str("biz_user_id"),data.str("user_id"))
            .firstOrNull { it.matches(Regex("[0-9]{1,30}")) }.orEmpty()
        val user=current.user.copy(
            name=data.str("user_name").ifEmpty { current.user.name },
            avatar=cleanUrl(data.str("expand_avatar_url").ifEmpty { data.str("avatar_url") }).ifEmpty { current.user.avatar },
            description=cleanText(data.str("description")).ifEmpty { current.user.description },
            mobile=maskMobile(data.str("mobile")).ifEmpty { current.user.mobile },
            accountId=accountId.ifEmpty { current.user.accountId }
        )
        synchronized(authLock) {
            if (authUser?.id != current.user.id || authToken != current.token || authCookies != current.cookies)
                throw IOException("登录账号已变化")
            authUser=user
        }
        return currentSession() ?: throw IOException("账号资料为空")
    }
    /** 账号接口统一要求 `lc` 头；签名仍由 [Signer] 负责。 */
    private fun accountHeaders() = mapOf("lc" to "101","x-ss-dp" to "8662")
    private fun responseCode(root: JSONObject) = when {
        root.has("code") -> root.optInt("code",-1)
        root.has("Code") -> root.optInt("Code",-1)
        else -> 0
    }
    private fun accountJson(url: String,values: Map<String,String>,session: AuthSession?=null): JSONObject {
        session?.let(::ensureHistorySession)
        val signed=signer.sign(url,values)
        val headers=accountHeaders()+if(session==null) emptyMap() else mapOf("Cookie" to cookieHeader(session.cookies),"X-Tt-Token" to session.token)
        val raw=text(signed.url,signed,headers)
        session?.let(::ensureHistorySession)
        val root=runCatching { JSONObject(raw) }.getOrElse { throw IOException("账号记录返回异常") }
        if(responseCode(root)!=0) throw IOException(authError(root))
        return root
    }
    /** Resolve legacy local records through an exact-ID match, never infer type from title keywords. */
    fun historyBookType(series: Series): Int {
        if(series.bookType in 2..5) return series.bookType
        RequestScope.ensureActive()
        val page=content.search(series.title)
        page.items.firstOrNull { it.id==series.id }?.bookType?.takeIf { it in 2..5 }?.let { return it }
        return 0
    }
    /** Captured official read_history/update/v JSON schema; position is milliseconds, vid_index is one-based. */
    fun uploadHistory(session: AuthSession,rows: List<HistoryCheckpoint>) {
        require(rows.isNotEmpty() && rows.size<=20)
        ensureHistorySession(session)
        val body=JSONObject().put("update_datas",JSONArray(rows.map { it.json() })).toString().toByteArray(Charsets.UTF_8)
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/read_history/update/v",recentOfficialValues(),body)
        // Explicit captured-session headers prevent a concurrent account switch from changing the recipient.
        val headers=accountHeaders()+mapOf("Cookie" to cookieHeader(session.cookies),"X-Tt-Token" to session.token)
        val raw=text(signed.url,signed,headers)
        val result=try { JSONObject(raw) } catch(_: Exception) { throw IOException("播放记录同步返回异常") }
        if(!result.has("code") && !result.has("Code")) throw IOException("播放记录同步未确认")
        if(responseCode(result)!=0) throw IOException(authError(result))
    }
    data class VideoProgress(val seriesId: String,val episodeId: String,val episodeIndex: Int,val position: Long,val readAt: Long)
    fun accountVideoProgress(session: AuthSession = currentSession() ?: throw IOException("请先登录")): List<VideoProgress> {
        ensureHistorySession(session)
        val values=recentOfficialValues().also { it["limit"]="0"; it["book_types"]="2,3,4"; it["last_min_read_timestamp_ms_list"]="" }
        val root=accountJson("https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/read_progress/list/v",values,session)
        ensureHistorySession(session)
        if(!root.has("code") && !root.has("Code")) throw IOException("续播进度读取未确认")
        return root.optJSONArray("video_data")?.objects().orEmpty().mapNotNull { row ->
            val id=row.str("series_id"); val episode=row.str("vid")
            if(id.isBlank() || episode.isBlank()) null else VideoProgress(id,episode,(row.optInt("vid_index",1)-1).coerceAtLeast(0),
                row.optLong("current_play_time",0).coerceAtLeast(0),row.optLong("last_watch_time",0))
        }
    }
    /** Read both stores: history is presentation metadata, progress is an independent resume source. */
    fun accountResumeHistory(session: AuthSession = currentSession() ?: throw IOException("请先登录"),known: List<Series> = emptyList()): AccountPage {
        val history=accountHistory(requireAllTypes=true,session=session)
        val progress=accountVideoProgress(session)
        val rows=history.history.associateBy { it.series.id }.toMutableMap()
        val metadata=known.associateBy { it.id }
        progress.forEach { remote ->
            val old=rows[remote.seriesId]
            val series=old?.series ?: metadata[remote.seriesId] ?: return@forEach
            if(remote.readAt>(old?.readAt ?: 0L)) rows[remote.seriesId]=AccountHistoryItem(series,remote.episodeId,remote.episodeIndex,
                remote.position,if(old?.episodeId==remote.episodeId) old.duration else 0,remote.readAt)
        }
        ensureHistorySession(session)
        val sorted=rows.values.sortedByDescending { it.readAt }
        return AccountPage(sorted.map { it.series },history=sorted)
    }
    fun uploadVideoProgress(session: AuthSession,rows: List<HistoryCheckpoint>) {
        require(rows.isNotEmpty() && rows.size<=20)
        ensureHistorySession(session)
        val body=JSONObject().put("books",JSONArray(rows.map { it.progressJson() })).toString().toByteArray(Charsets.UTF_8)
        val signed=signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/read_progress/upload/v",recentOfficialValues(),body)
        val headers=accountHeaders()+mapOf("Cookie" to cookieHeader(session.cookies),"X-Tt-Token" to session.token)
        val root=try { JSONObject(text(signed.url,signed,headers)) } catch(_: Exception) { throw IOException("续播进度返回异常") }
        if(!root.has("code") && !root.has("Code")) throw IOException("续播进度上传未确认")
        if(responseCode(root)!=0) throw IOException(authError(root))
    }
    fun uploadPlaybackCheckpoint(session: AuthSession,rows: List<HistoryCheckpoint>) {
        uploadHistory(session,rows)
        uploadVideoProgress(session,rows)
    }
    private fun ensureHistorySession(session: AuthSession) {
        val active=currentSession()
        if(active?.user?.id!=session.user.id || active.token!=session.token || active.cookies!=session.cookies)
            throw IOException("登录账号已变化，停止同步")
    }
    private fun firstText(o: JSONObject,vararg keys: String): String {
        for(key in keys) { val value=o.str(key); if(value.isNotBlank()) return value }
        return ""
    }
    /** 兼容布尔、0/1 与 "true"/"false" 三种字段写法。 */
    private fun accountBool(o: JSONObject,key: String,default: Boolean=false): Boolean {
        if(!o.has(key) || o.isNull(key)) return default
        return when(val value=o.opt(key)) {
            is Boolean -> value
            is Number -> value.toInt()!=0
            is String -> value.equals("true",ignoreCase=true) || value=="1"
            else -> default
        }
    }
    private fun numericId(vararg values: String) = values.firstOrNull { it.matches(Regex("[0-9]{1,30}")) }.orEmpty()
    /** 接口既可能返回数组，也可能把记录放在对象里；统一展开成行列表。 */
    private fun accountRows(data: JSONObject,vararg keys: String): List<JSONObject> {
        for(key in keys) {
            data.optJSONArray(key)?.let { return it.objects() }
            data.optJSONObject(key)?.let { holder ->
                val nested=holder.optJSONArray("list") ?: holder.optJSONArray("items") ?: holder.optJSONArray("data_list")
                if(nested!=null) return nested.objects()
                if(firstText(holder,"book_id","series_id","item_id","id").isNotBlank()) return listOf(holder)
                if(holder.length()>0) return holder.keys().asSequence().mapNotNull { holder.optJSONObject(it) }.toList()
            }
        }
        return emptyList()
    }
    private fun accountTagList(o: JSONObject,vararg keys: String): List<String> {
        for(key in keys) {
            val array=o.optJSONArray(key) ?: continue
            val values=(0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { firstText(it,"name","text","content","title") }
                    ?: array.optString(index).trim().takeIf { it.isNotEmpty() }
            }.filter { it.isNotBlank() }
            if(values.isNotEmpty()) return values
        }
        return emptyList()
    }
    /**
     * 账号观看记录。官方客户端按 `book_type` 分请求，短剧/漫剧/其他类型需要合并；
     * 这里按最近阅读时间排序并按剧集去重，避免同一部剧在多个类型里重复出现。
     */
    fun accountHistory(requireAllTypes: Boolean = false,session: AuthSession = currentSession() ?: throw IOException("请先登录")): AccountPage {
        ensureHistorySession(session)
        var succeeded=false
        var failure: Throwable?=null
        val records=linkedMapOf<String,AccountHistoryItem>()
        for(bookType in listOf("2","3","4","5")) {
            ensureHistorySession(session)
            val values=recentOfficialValues().also { map ->
                map["offset"]="0"; map["limit"]="30"; map["book_type"]=bookType
                map["query_soft_deleted"]="false"; map["is_first_load"]="false"
                map["last_min_read_timestamp_ms"]="0"; map["full_field"]="false"
            }
            val root=try { accountJson(HISTORY_API,values,session) } catch(problem: Exception) {
                RequestScope.ensureActive()
                failure=failure ?: problem
                continue
            }
            succeeded=true
            val data=root.optJSONObject("data") ?: continue
            for(row in data.optJSONArray("data_list")?.objects().orEmpty()) {
                val item=accountHistoryItem(row,bookType.toInt()) ?: continue
                val previous=records[item.series.id]
                if(previous==null || item.readAt>previous.readAt) records[item.series.id]=item
            }
        }
        ensureHistorySession(session)
        if(requireAllTypes && failure!=null) throw failure
        if(!succeeded) throw failure ?: IOException("账号记录暂时不可用")
        val history=records.values.sortedByDescending { it.readAt }
        return AccountPage(history.map { it.series },history=history)
    }
    private fun accountHistoryItem(row: JSONObject,bookType: Int): AccountHistoryItem? {
        if(accountBool(row,"is_delete")) return null
        val video=row.optJSONObject("video_data")
        val id=numericId(
            video?.str("series_id").orEmpty(),video?.str("book_id_str").orEmpty(),row.str("book_id_str"),row.str("book_id"),
            video?.str("book_id").orEmpty()
        )
        val title=cleanText(firstText(video ?: JSONObject(),"series_title","title","book_name")
            .ifEmpty { firstText(row,"series_title","book_name","title","name") })
        if(id.isBlank() || title.isBlank()) return null
        val cover=cleanUrl(firstText(row,"thumb_url","cover","cover_url","image_url")
            .ifEmpty { firstText(video ?: JSONObject(),"series_cover","cover","thumb_url") })
        val description=cleanText(firstText(video ?: JSONObject(),"series_intro","description","abstract")
            .ifEmpty { firstText(row,"series_intro","book_abstract","description") })
        val count=video?.optInt("episode_cnt",0)?.takeIf { it>0 } ?: row.optInt("episode_cnt",0)
        val update=row.optJSONObject("update_info")?.let { firstText(it,"update_tag_text","tag_text") }.orEmpty()
        val badge=update.ifEmpty {
            firstText(row,"episode_right_text").ifEmpty { if(count>0) "全 $count 集" else "" }
        }
        val tags=accountTagList(row,"category_list","tags").joinToString(" · ")
        val series=Series(id,title,cover,description,badge,tags,preview=false,bookType=bookType)
        return AccountHistoryItem(
            series=series,
            episodeId=row.str("vid").ifEmpty { row.str("video_id") },
            episodeIndex=(row.optInt("vid_index",1)-1).coerceAtLeast(0),
            position=row.optLong("current_play_position",0).coerceAtLeast(0),
            duration=row.optLong("duration",0).coerceAtLeast(0),
            readAt=row.optLong("read_timestamp_ms",0)
        )
    }
    /** 账号收藏：短剧收藏在 `bookshelf/video/list/v` 的 `video_shelf_info` 中。 */
    fun accountFavorites(): AccountPage {
        val session=currentSession() ?: throw IOException("请先登录")
        val userId=session.user.accountId
        if(userId.isBlank()) throw IOException("账号资料缺少用户标识，请到账号页刷新资料后重试")
        val values=recentOfficialValues().also { map ->
            map["server_time"]=(clock()/1000).toString()
            map["target_user_id"]=userId
        }
        val root=accountJson(FAVORITES_API,values,session)
        val data=root.optJSONObject("data") ?: return AccountPage(emptyList())
        val items=accountRows(data,"video_shelf_info","video_shelf","book_list","data_list","books")
            .mapNotNull(::accountShelfItem).distinctBy { it.id }
        return AccountPage(items)
    }
    private fun accountShelfItem(row: JSONObject): Series? {
        val nested=row.optJSONObject("video_data") ?: row.optJSONObject("series_info")
            ?: row.optJSONObject("book_info") ?: row
        val id=numericId(
            firstText(nested,"series_id","book_id_str","book_id","item_id","id"),
            firstText(row,"book_id_str","series_id","book_id","item_id","id")
        )
        val title=cleanText(firstText(nested,"series_title","book_name","title","name")
            .ifEmpty { firstText(row,"series_title","book_name","title","name") })
        if(id.isBlank() || title.isBlank()) return null
        val cover=cleanUrl(firstText(nested,"series_cover","cover","thumb_url","cover_url","image_url")
            .ifEmpty { firstText(row,"series_cover","cover","thumb_url","cover_url","image_url") })
        val description=cleanText(firstText(nested,"series_intro","description","abstract")
            .ifEmpty { firstText(row,"series_intro","description","abstract") })
        val count=nested.optInt("episode_cnt",row.optInt("episode_cnt",0))
        val update=firstText(nested,"episode_right_text","update_tag_text","rec_text")
        val badge=update.ifEmpty { if(count>0) "全 $count 集" else "" }
        val tags=accountTagList(nested,"tags","category_list").ifEmpty {
            accountTagList(row,"tags","category_list")
        }.joinToString(" · ")
        return Series(id,title,cover,description,badge,tags,preview=false)
    }
    /** 账号预约：待上线与已上线分别请求，合并后按上线状态和预计时间排序。 */
    fun accountReservations(): AccountPage {
        val session=currentSession() ?: throw IOException("请先登录")
        var succeeded=false
        var failure: Throwable?=null
        val records=linkedMapOf<String,AccountReservation>()
        for(online in listOf(false,true)) {
            val values=recentOfficialValues().also { map ->
                map["offset"]="0"; map["limit"]="30"; map["is_online"]=online.toString()
                map["tab_type"]="13"; map["subscribe_order_type"]="0"; map["swipe_type"]="0"
                map["need_calendar_schema"]="false"; map["subscribe_offset"]="0"
            }
            val root=try { accountJson(RESERVATIONS_API,values,session) } catch(problem: Exception) {
                failure=failure ?: problem
                continue
            }
            succeeded=true
            val data=root.optJSONObject("data") ?: continue
            for(row in accountRows(data,"subscribe_items","items","list")) {
                val item=accountReservationItem(row) ?: continue
                records[item.series.id]=item
            }
        }
        ensureHistorySession(session)
        if(!succeeded) throw failure ?: IOException("预约记录暂时不可用")
        val reservations=records.values.sortedWith(
            compareBy<AccountReservation> { it.online }
                .thenBy { if(it.expectedAt>0) it.expectedAt else Long.MAX_VALUE }
                .thenBy { it.series.title }
        )
        return AccountPage(reservations.map { it.series },reservations=reservations)
    }
    private fun accountReservationItem(row: JSONObject): AccountReservation? {
        val id=numericId(row.str("item_id").ifEmpty { row.str("item_id_str") },row.str("series_id"),row.str("book_id"))
        val title=cleanText(firstText(row,"name","title","book_name","series_name"))
        if(id.isBlank() || title.isBlank()) return null
        val cover=cleanUrl(firstText(row,"cover","thumb_url","cover_url","image_url"))
        val description=cleanText(firstText(row,"item_desc","description","summary"))
        val online=accountBool(row,"is_online")
        val expectedAt=row.optLong("expected_publish_time",
            row.optLong("schedule_publish_time",row.optLong("publish_time",0)))
        val expectedText=cleanText(firstText(row,"expected_rec_text","publish_time_text","rec_text"))
        val tag=cleanText(row.optJSONObject("tag_info")?.let { firstText(it,"text","name") }.orEmpty())
        val tags=accountTagList(row,"categories","tags","sub_title_list").ifEmpty {
            listOfNotNull(tag.takeIf { it.isNotBlank() })
        }.joinToString(" · ")
        val badge=listOf(
            expectedText.ifBlank { if(online) "已上线" else "待上线" },
            tag
        ).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        val series=Series(id,title,cover,description,badge,tags,preview=!online)
        return AccountReservation(
            series=series,
            itemType=row.optInt("item_type",14),
            online=online,
            expectedAt=expectedAt,
            subscribed=accountBool(row,"has_subscribed",true),
            expectedText=expectedText
        )
    }
    /**
     * 预约/取消预约。官方请求用 `op_type=1` 表示预约、`2` 表示取消，
     * `item_type=14` 对应短剧预约项；`wish_list_all_del=0` 避免清空其它预约。
     */
    fun setReservation(itemId: String, active: Boolean, itemType: Int = 14): Boolean {
        if(!active && itemId.isBlank()) throw IllegalArgumentException("预约信息为空")
        require(itemId.matches(Regex("[0-9]{1,30}"))) { "预约信息无效" }
        require(itemType in 1..99) { "预约类型无效" }
        val session=currentSession() ?: throw IOException("请先登录")
        val numericId=itemId.toLongOrNull() ?: throw IllegalArgumentException("预约信息无效")
        val body=JSONObject()
            .put("item_id",numericId)
            .put("item_type",itemType)
            .put("op_type",if(active) 1 else 2)
            .put("item_id_str",itemId)
            .put("wish_list_all_del",0)
            .put("shark_param",JSONObject()
                .put("enter_from","ShortSeriesActivity")
                .put("previous_page","MainFragmentActivity")
                .put("page_list","MainFragmentActivity,ShortSeriesActivity"))
        val signed=signer.sign(RESERVATION_API,recentOfficialValues(),body.toString().toByteArray(Charsets.UTF_8))
        val raw=text(signed.url,signed,accountHeaders()+mapOf("Cookie" to cookieHeader(session.cookies),"X-Tt-Token" to session.token))
        val root=runCatching { JSONObject(raw) }.getOrElse { throw IOException("预约服务返回异常") }
        if(responseCode(root)!=0) throw IOException(authError(root))
        return active
    }
    fun reserve(itemId: String,itemType: Int = 14)=setReservation(itemId,true,itemType)
    fun cancelReservation(itemId: String,itemType: Int = 14)=setReservation(itemId,false,itemType)
    private fun qrUrl(base: String,token: String): String {
        val values=linkedMapOf(
            "aid" to QR_AID,
            "next" to QR_WEB,
            "language" to "zh",
            "account_sdk_source" to "web",
            "request_host" to QR_WEB,
            "passport_jssdk_version" to "2.4.13",
            "passport_jssdk_type" to "normal",
            "is_from_ttaccountsdk" to "1",
            "p_js_v" to "2.4.13",
            "p_js_t" to "pro",
            "p_zt" to "3.3.5",
            "p_ver" to "1.0.29",
            "p_bd" to "1.0.1.20"
        )
        if(token.isNotBlank()) values["token"]=token
        return base+"?"+Signer.encode(values)
    }
    private fun qrJson(client: OkHttpClient,store: QrCookieStore,url: String,appToken: String): Pair<JSONObject,String> {
        val httpUrl=url.toHttpUrlOrNull() ?: throw IOException("二维码地址异常")
        val builder=Request.Builder().url(httpUrl)
            .header("User-Agent",QR_UA)
            .header("Accept","application/json, text/plain, */*")
            .header("Accept-Language","zh-CN,zh;q=0.9")
            .header("Referer",QR_WEB)
            .get()
        val cookie=store.header(httpUrl)
        if(cookie.isNotBlank()) builder.header("Cookie",cookie)
        val csrf=store.snapshot()["passport_csrf_token"].orEmpty()
        if(csrf.isNotBlank()) builder.header("x-tt-passport-csrf-token",csrf)
        if(appToken.isNotBlank()) builder.header("X-Tt-Token",appToken)
        return RequestScope.execute(client.newCall(builder.build())) { response ->
            store.save(response)
            if(!response.isSuccessful) throw IOException("二维码服务请求失败（HTTP ${response.code}）")
            val root=runCatching { JSONObject(responseText(response)) }.getOrElse { throw IOException("二维码服务返回异常") }
            root to response.header("X-Tt-Token").orEmpty()
        }
    }
    private fun followQrRedirects(client: OkHttpClient,store: QrCookieStore,start: String,appToken: String): String {
        var current=start.toHttpUrlOrNull() ?: throw IOException("扫码登录跳转地址异常")
        var token=appToken
        repeat(6) {
            val host=current.host
            if(!current.isHttps || !(host=="hongguoduanju.com" || host.endsWith(".hongguoduanju.com") ||
                host=="snssdk.com" || host.endsWith(".snssdk.com") || host=="fqnovel.com" || host.endsWith(".fqnovel.com")))
                throw IOException("扫码登录跳转地址不受信任")
            val builder=Request.Builder().url(current)
                .header("User-Agent",QR_UA)
                .header("Accept","text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language","zh-CN,zh;q=0.9")
                .header("Referer",QR_WEB)
                .get()
            val cookie=store.header(current)
            if(cookie.isNotBlank()) builder.header("Cookie",cookie)
            val next=RequestScope.execute(client.newCall(builder.build())) { response ->
                store.save(response)
                val headerToken=response.header("X-Tt-Token").orEmpty()
                if(headerToken.isNotBlank()) token=headerToken
                if(response.code in 300..399) {
                    val location=response.header("Location").orEmpty()
                    if(location.isBlank()) throw IOException("扫码登录跳转地址异常")
                    current.resolve(location) ?: throw IOException("扫码登录跳转地址异常")
                } else {
                    if(!response.isSuccessful) throw IOException("扫码登录落地失败（HTTP ${response.code}）")
                    null
                }
            }
            if(next==null) return token
            current=next
        }
        throw IOException("扫码登录跳转次数过多")
    }
    private fun qrStatus(raw: String,hasRedirect: Boolean): LoginQrStatus? {
        return when(raw.trim().lowercase()) {
            "new","waiting","0","1" -> LoginQrStatus.NEW
            "scanned","2" -> LoginQrStatus.SCANNED
            "confirmed" -> LoginQrStatus.CONFIRMED
            "expired","4","5" -> LoginQrStatus.EXPIRED
            "3" -> if(hasRedirect) LoginQrStatus.CONFIRMED else null
            else -> if(hasRedirect) LoginQrStatus.CONFIRMED else null
        }
    }
    private fun hasLoginCookie(cookies: Map<String,String>) = listOf("sessionid","sessionid_ss","sid_tt","sid_guard")
        .any { !cookies[it].isNullOrBlank() }
    /**
     * 扫码确认后先建立临时会话，再尝试读取账号资料。资料接口暂时不可用时保留一个
     * 不含昵称的本地会话，界面显示通用登录提示；只有服务端明确未登录才清空。
     */
    private fun qrUserId(cookies: Map<String,String>): String {
        for(key in listOf("uid_tt","uid_tt_ss","sso_uid_tt","sid_tt")) {
            cookies[key]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val session=cookies["sessionid"].takeUnless { it.isNullOrBlank() }
            ?: cookies["sessionid_ss"].orEmpty()
        if(session.isBlank()) throw IOException("登录状态未返回")
        // 仅用于本地会话标识，不显示、不写日志；避免把 sessionid 本身存成 id。
        return java.util.UUID.nameUUIDFromBytes(session.toByteArray(Charsets.UTF_8)).toString()
    }
    private fun authExpired(problem: Throwable): Boolean {
        val text=problem.message.orEmpty()
        return text.contains("未登录") || text.contains("请先登录") || text.contains("登录已过期") ||
            text.contains("HTTP 401") || text.contains("HTTP 403")
    }
    private fun qrImage(value: String): String {
        val raw=value.trim()
        if(raw.startsWith("data:image/")) return raw
        if(raw.contains("://")) throw IOException("二维码图片格式异常")
        require(raw.isNotBlank()) { "二维码图片为空" }
        return "data:image/png;base64,$raw"
    }
    private fun qrError(root: JSONObject): String {
        val data=root.optJSONObject("data")
        val description=data?.str("description").orEmpty()
        val message=root.str("message").orEmpty()
        val code=data?.optInt("error_code",0) ?: 0
        val text=description.ifBlank { message }.takeUnless { it.equals("success",ignoreCase=true) }.orEmpty()
        return when {
            text.isNotBlank() -> redactSensitive(text).take(160)
            code!=0 -> "二维码服务返回错误（$code）"
            else -> "二维码服务暂时不可用"
        }
    }
    private fun maskMobile(value: String): String {
        val digits=value.filter { it.isDigit() }
        val eleven=when {
            digits.length==11 -> digits
            digits.length==13 && digits.startsWith("86") -> digits.substring(2)
            digits.length>11 -> digits.takeLast(11)
            else -> ""
        }
        return if(eleven.length==11) "${eleven.take(3)}******${eleven.takeLast(2)}" else ""
    }
    private fun cookieHeader(cookies: Map<String,String>) = cookies.entries
        .filter { it.value.isNotBlank() }.joinToString("; ") { (key,value) -> "$key=$value" }
    private fun authError(root: JSONObject): String {
        val data=root.optJSONObject("data")
        val description=data?.str("description").orEmpty()
        val message=root.str("message").orEmpty()
        val code=data?.optInt("error_code",0) ?: 0
        val text=description.ifBlank { message }.takeUnless { it.equals("success",ignoreCase=true) }.orEmpty()
        return when {
            text.isNotBlank() -> redactSensitive(text).take(160)
            code!=0 -> "账号服务返回错误（$code）"
            else -> "账号服务暂时不可用"
        }
    }
    /** 错误提示可能来自服务端；把手机号、二维码 token 和会话键值一并遮掉。 */
    private fun redactSensitive(text: String) = redactPhone(text.replace(
        Regex("(?i)(token|sessionid(?:_ss)?|sid_tt|sid_guard|uid_tt(?:_ss)?|d_ticket)=([^&;\\s]+)")
    ) { "${it.groupValues[1]}=***" })
    private fun redactPhone(text: String) = text.replace(Regex("(?<!\\d)(1[3-9]\\d{9})(?!\\d)")) {
        "${it.value.take(3)}******${it.value.takeLast(2)}"
    }
}
