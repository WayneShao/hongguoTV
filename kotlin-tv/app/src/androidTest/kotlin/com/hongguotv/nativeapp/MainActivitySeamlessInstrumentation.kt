// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.hongguotv.core.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Injects fixture resolution only; executes MainActivity's real play/transition/history code. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun Instrumentation.runMainActivitySeamless(scenario: String = "main") {
        val report=JSONObject().put("scenario",scenario); val events=JSONArray(); report.put("events",events)
        val result=Bundle(); var code=Activity.RESULT_CANCELED
        var activity: Activity?=null
        val executor=Executors.newSingleThreadExecutor(); val client=fixtureClient()
        var gate=PreloadGate {SystemClock.elapsedRealtime()}
        val config=PreloadSettings(mode=PreloadMode.EPISODES,episodes=5,enabled=true)
        val ids=listOf("990000001","990000002","990000003")
        val dir=File(targetContext.cacheDir,"main-seamless")
        report.put("priorInterruptedRunFiles",dir.listFiles()?.size ?: 0)
        dir.listFiles()?.forEach {check(it.delete())}
        val preloader by lazy { EpisodePreloader(executor,client,{dir},{key -> StreamInfo("http://fixture.byteicdn.com:18765/${ids.indexOf(key.substringBefore(':'))}",null,"480P")},gate,{SystemClock.elapsedRealtime()}) }
        fun field(name: String)=MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}
        try {
            check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {"Emulator only"}
            activity=startActivitySync(Intent().setClassName(targetContext.packageName,"com.hongguotv.nativeapp.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitForIdleSync()
            val app=activity!!
            gate=field("preloadGate").get(app) as PreloadGate
            var quality=1080
            runOnMainSync { quality=(field("library").get(app) as Library).maxQuality }
            preloader.configure(config); preloader.update(ids.map {"$it:$quality"});gate.update(true,60000,60000)
            val deadline=SystemClock.elapsedRealtime()+30000
            while(preloader.cachedBytes()<805825L && SystemClock.elapsedRealtime()<deadline) {preloader.pump();SystemClock.sleep(100)}
            // count includes in-flight jobs: wait until every fixture is fully cached through the byte total.
            check(preloader.cachedBytes()==805825L) {"Incomplete fixture cache ${preloader.cachedBytes()}"}
            check(preloader.count()==3) {"Fixture window count ${preloader.count()}"}
            report.put("prefilledCount",preloader.count()).put("prefilledBytes",preloader.cachedBytes())
            runOnMainSync {
                val previous=field("upcoming\$delegate").get(app) as Lazy<*>
                if(previous.isInitialized()) (previous.value as EpisodePreloader).close()
                field("upcoming\$delegate").set(app,lazyOf(preloader))
                field("detail").set(app,Detail(Series("990000000","Local seamless regression"),ids))
                field("preloadConfig").set(app,config)
                (field("library").get(app) as Library).autoNext=true
                if(scenario in listOf("main-timer","main-replay")) (field("sleepTimer").get(app) as SleepTimer).afterEpisodes(1)
                if(scenario=="main-manual") (field("sleepTimer").get(app) as SleepTimer).afterEpisodes(3)
                MainActivity::class.java.getDeclaredMethod("playEpisode",Int::class.javaPrimitiveType,Long::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(app,0,0L,true,false)
            }
            var original: ExoPlayer?=null; var ended=false;var autoTransitions=0;var seenIndex=0;var phase=0;var boundaryAt=0L;var boundaryPosition=0L
            val playDeadline=SystemClock.elapsedRealtime()+40000
            while(SystemClock.elapsedRealtime()<playDeadline && !ended) {
                runOnMainSync {
                    val p=field("player").get(app) as ExoPlayer?
                    if(p!=null && original==null) {
                        original=p
                        p.addListener(object: Player.Listener {
                            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) autoTransitions++ }
                        })
                    }
                    if(p!=null) {
                        check(original===p) {"Player replaced across natural transition"}
                        val index=field("episodeIndex").getInt(app)
                        if(index!=seenIndex) {
                            events.put(JSONObject().put("index",index).put("mediaId",p.currentMediaItem?.mediaId)
                                .put("title",(field("playbackTitle").get(app) as TextView).text.toString())
                                .put("positionMs",p.currentPosition).put("queueIds",JSONArray((field("playbackQueue").get(app) as SeamlessPlaybackQueue).mediaIds)))
                            check(p.currentMediaItem?.mediaId==ids[index]) {"UI/player episode mismatch"}
                            check((field("playbackTitle").get(app) as TextView).text.contains("${index+1}"))
                            seenIndex=index
                        }
                        check(!field("playError").getBoolean(app)) {"Production playback failure"}
                        val q=field("playbackQueue").get(app) as SeamlessPlaybackQueue
                        val completed=field("completedEpisodes").get(app) as Set<*>
                        val timer=field("sleepTimer").get(app) as SleepTimer
                        fun invokePlay(next: Int) = MainActivity::class.java.getDeclaredMethod("playEpisode",Int::class.javaPrimitiveType,Long::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(app,next,0L,true,false)
                        if(scenario in listOf("main-timer","main-replay") && field("sleepStopped").getBoolean(app)) {
                            check(index==0 && p.currentMediaItem?.mediaId==ids[0] && !p.playWhenReady && timer.episodesLeft==0)
                            check(completed.contains(ids[0]) && autoTransitions==0)
                            if(phase==0) {
                                report.put("timerStoppedBeforeNext",true)
                                if(scenario=="main-timer") {
                                    check(q.nextMediaId==ids[1]) {"Timer test missed queued next before ticker cleanup"}
                                    invokePlay(1)
                                    check(completed.contains(ids[0])) {"Manual leave erased completed state"}
                                    check(app.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
                                    report.put("manualAfterSleepPreservedCompletion",true).put("manualKeepScreenOn",true)
                                } else {
                                    MainActivity::class.java.getDeclaredMethod("seekTo",Long::class.javaPrimitiveType).apply {isAccessible=true}.invoke(app,0L)
                                    check(!completed.contains(ids[0])) {"Replay did not clear completion marker"}
                                    timer.afterEpisodes(1)
                                    MainActivity::class.java.getDeclaredMethod("requestPlayback",Boolean::class.javaPrimitiveType).apply {isAccessible=true}.invoke(app,true)
                                    report.put("replayCompletionReset",true)
                                }
                                phase=1
                            } else if(scenario=="main-replay") {report.put("replayedEpisodeCountedAgain",true);ended=true}
                        }
                        if(scenario=="main-timer" && phase==1 && index==1 && p.isPlaying) ended=true
                        if(scenario=="main-manual" && phase==0 && q.nextMediaId==ids[1] && p.currentPosition>500) {
                            invokePlay(1)
                            check(timer.episodesLeft==3 && !completed.contains(ids[0])) {"Manual skip counted a completion"}
                            report.put("manualSkipPreservedTimer",true);phase=1
                        }
                        if(scenario=="main-manual" && phase==1 && index==1 && p.isPlaying) ended=true
                        if(scenario=="main-disable" && phase==0 && q.nextMediaId==ids[1]) {
                            val current=q.remoteFor(ids[0])!!
                            field("preloadConfig").set(app,config.copy(enabled=false))
                            MainActivity::class.java.getDeclaredMethod("cancelPrefetch").apply {isAccessible=true}.invoke(app)
                            check(q.nextMediaId==null && q.remoteFor(ids[0])===current && current.reusable)
                            boundaryAt=SystemClock.elapsedRealtime();boundaryPosition=p.currentPosition;phase=1
                        }
                        if(scenario=="main-disable" && phase==1 && SystemClock.elapsedRealtime()-boundaryAt>500) {
                            check(p.isPlaying && p.currentPosition>boundaryPosition && q.mediaIds==listOf(ids[0]))
                            check(preloader.pinnedCount()==0) {"Disabled next remains pinned"}
                            report.put("disabledPreloadCurrentContinues",true).put("releasedNextPin",true);ended=true
                        }
                        if(scenario=="main") ended=index==2 && p.playbackState==Player.STATE_ENDED
                    }
                }
                SystemClock.sleep(50)
            }
            check(ended) {"Production episode sequence did not finish"}
            if(scenario=="main") check(autoTransitions==2) {"Expected two natural AUTO transitions, got $autoTransitions"}
            runOnMainSync {
                val completed=field("completedEpisodes").get(app) as Set<*>
                report.put("completedEpisodes",JSONArray(completed.toList()))
                if(scenario=="main") check(completed.containsAll(ids)) {"Episode completion state missing: $completed"}
                report.put("finalEpisodeIndex",field("episodeIndex").getInt(app))
                report.put("playerRetained",true).put("autoTransitions",autoTransitions)
            }
            report.put("passed",true);code=Activity.RESULT_OK
        } catch(failure: Throwable) {report.put("passed",false).put("error",failure.stackTraceToString())}
        finally {
            activity?.let { app ->
                runOnMainSync { app.finish() }
                val deadline=SystemClock.elapsedRealtime()+5000
                while(!app.isDestroyed && SystemClock.elapsedRealtime()<deadline) { waitForIdleSync();SystemClock.sleep(50) }
                report.put("activityDestroyed",app.isDestroyed)
            }
            waitForIdleSync()
            preloader.close();executor.shutdownNow();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
            val remaining=dir.listFiles()?.size ?: 0
            report.put("remainingCacheFiles",remaining)
            if(remaining!=0) {report.put("passed",false).put("cleanupError","Cache files remain after activity destruction");code=Activity.RESULT_CANCELED}
            File(targetContext.filesDir,"main-seamless-report.json").writeText(report.toString(2))
        }
        result.putString("stream",report.toString(2));finish(code,result)
    }
