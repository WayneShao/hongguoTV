package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import com.hongguotv.core.*
import java.io.File
import org.json.JSONObject

/** Exercises packaged/desugared production code on an isolated Android emulator. */
class LegacyTvInstrumentation : Instrumentation() {
    private var arguments=Bundle()
    override fun onCreate(arguments: Bundle?) {super.onCreate(arguments);this.arguments=arguments ?: Bundle();start()}
    override fun onStart() {
        val report=JSONObject().put("api",android.os.Build.VERSION.SDK_INT)
        var activity: Activity?=null
        var code=Activity.RESULT_CANCELED
        try {
            check(android.os.Build.MODEL.contains("sdk",ignoreCase=true))
            activity=startActivitySync(Intent().setClassName(targetContext.packageName,"com.hongguotv.nativeapp.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync();report.put("activityStarted",true)
            val manager=activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            report.put("networkAvailable",NetworkCompat.available(manager)).put("lanAddress",NetworkCompat.lanAddress(manager))
            check(NetworkCompat.available(manager))
            report.put("lanNote","API21 AVD advertises cellular, so LAN-only QR is correctly unavailable")
            // Actual Java 8 Base64 and NIO backports in the APK, not the desktop JVM.
            val bytes="legacy-update-probe".toByteArray()
            val encoded=java.util.Base64.getEncoder().encodeToString(bytes)
            check(java.util.Base64.getDecoder().decode(encoded).contentEquals(bytes))
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val value=AppUpdate(999,"0.0.0-local",21,"personal-0.0.0","hongguoTV-TV-0.0.0.apk",bytes.size.toLong(),hash,"")
            val file=File(targetContext.cacheDir,"legacy-update/check.apk")
            try {UpdateFiles.receive(bytes.inputStream(),file,value);check(UpdateFiles.verify(file,value))}finally{file.parentFile?.deleteRecursively()}
            report.put("base64AndAtomicUpdateFile",true)
            val dns=object: okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    val address=arguments.getString(hostname)
                    return if(address!=null) listOf(java.net.InetAddress.getByName(address)) else okhttp3.Dns.SYSTEM.lookup(hostname)
                }
            }
            report.put("hostDnsOverrides",arguments.keySet().joinToString())
            AppUpdateRepository(configure={it.dns(dns)}).use {repo -> report.put("githubLatest",repo.latest().tag)}
            val repo=ContentRepository(HttpTransport.builder().dns(dns).connectTimeout(10,java.util.concurrent.TimeUnit.SECONDS).callTimeout(25,java.util.concurrent.TimeUnit.SECONDS).build())
            try {check(repo.search("总裁").items.isNotEmpty());report.put("catalogHttps",true)}
            finally {repo.http.dispatcher.cancelAll();repo.http.connectionPool.evictAll();repo.http.dispatcher.executorService.shutdown()}
            lateinit var surface: android.view.SurfaceView
            val surfaceReady=java.util.concurrent.CountDownLatch(1)
            runOnMainSync {
                surface=android.view.SurfaceView(activity)
                surface.holder.addCallback(object: android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder){surfaceReady.countDown()}
                    override fun surfaceChanged(holder: android.view.SurfaceHolder,format: Int,w: Int,h: Int){}
                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder){}
                })
                activity.setContentView(surface)
            }
            check(surfaceReady.await(10,java.util.concurrent.TimeUnit.SECONDS))
            val client=HttpTransport.builder().build()
            val cache=File(targetContext.cacheDir,"legacy-playback")
            val videos=(0..2).map { RemoteVideo(client,StreamInfo("http://10.0.2.2:18765/clean/$it",null,"480P")).preload(cache,{false}) }
            val ended=java.util.concurrent.CountDownLatch(1)
            var error: String?=null;var transitions=0
            var player: androidx.media3.exoplayer.ExoPlayer?=null;var queue: SeamlessPlaybackQueue?=null
            try {
                runOnMainSync {
                    val p=androidx.media3.exoplayer.ExoPlayer.Builder(activity).build();player=p
                    val audioDisabled=arguments.getString("disableAudio")=="true"
                    if(audioDisabled) p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO,true).build()
                    report.put("audioDisabled",audioDisabled)
                    val q=SeamlessPlaybackQueue(p);queue=q
                    p.volume=0f;p.setVideoSurfaceView(surface)
                    p.addListener(object: androidx.media3.common.Player.Listener {
                        override fun onPlayerError(e: androidx.media3.common.PlaybackException){error=e.toString();ended.countDown()}
                        override fun onPlaybackStateChanged(state: Int){if(state==androidx.media3.common.Player.STATE_ENDED)ended.countDown()}
                        override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?,reason: Int){
                            if(reason==androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                                transitions++;q.advanceTo(item!!.mediaId)
                                if(transitions==1)check(q.appendNext("2",videos[2]))
                            }
                        }
                    })
                    q.installCurrent("0",videos[0]);check(q.appendNext("1",videos[1]));p.prepare();p.play()
                }
                val finished=ended.await(35,java.util.concurrent.TimeUnit.SECONDS)
                runOnMainSync {report.put("playbackState",player!!.playbackState).put("positionMs",player!!.currentPosition).put("autoTransitions",transitions).put("isPlaying",player!!.isPlaying)}
                check(finished){"Playback timeout"}
                check(error==null){error!!};check(transitions==2)
                report.put("hevcThreeEpisodes",true).put("autoTransitions",transitions)
            } finally {
                runOnMainSync {player?.release();queue?.releaseAfterPlayerRelease()}
                videos.forEach{it.close()};client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
            }
            runOnMainSync {
                val field=MainActivity::class.java.getDeclaredField("updater").apply{isAccessible=true}
                val updater=field.get(activity) as AppUpdater
                updater.show(activity.currentFocus);check(updater.showing);updater.pause()
            }
            report.put("updateDialog",true)
            report.put("passed",true);code=Activity.RESULT_OK
        } catch(error: Throwable){report.put("passed",false).put("error",error.stackTraceToString())}
        finally {
            File(targetContext.filesDir,"legacy-tv-report.json").writeText(report.toString(2))
            activity?.let {runOnMainSync {it.finish()}}
        }
        finish(code,Bundle().apply{putString("stream",report.toString(2))})
    }
}
