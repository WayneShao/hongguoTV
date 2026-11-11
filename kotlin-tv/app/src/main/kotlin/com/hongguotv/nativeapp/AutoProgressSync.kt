// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hongguotv.core.AccountContext
import com.hongguotv.core.AccountRepository

/**
 * Gate for record-page triggered automatic progress merges. It never owns a timer: each page
 * load calls [recordsLoaded], and only an accepted merge consumes the per-owner interval.
 */
internal class AutoProgressSync(
    context: Context,
    private val repository: AccountRepository,
    private val start: (AccountContext,(AccountProgressMerge.Result)->Unit)->Boolean,
    private val updated: (AccountContext,AccountProgressMerge.Result)->Unit,
    private val clock: ()->Long = System::currentTimeMillis,
) {
    companion object {
        val intervalOptions=listOf(1,5,10,15,30,60)
        private const val FILE="auto-progress-sync-v1"
        private const val ENABLED="enabled"
        private const val INTERVAL="intervalMinutes"
        private const val LAST="last:"
    }
    private class Operation(val context: AccountContext)
    private val prefs=context.applicationContext.getSharedPreferences(FILE,Context.MODE_PRIVATE)
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var closed=false
    private var operation: Operation?=null

    var enabled: Boolean
        get()=prefs.getBoolean(ENABLED,false)
        set(value) { prefs.edit().putBoolean(ENABLED,value).apply() }

    var intervalMinutes: Int
        get()=prefs.getInt(INTERVAL,5).takeIf { it in intervalOptions } ?: 5
        set(value) {
            prefs.edit().putInt(INTERVAL,value.takeIf { it in intervalOptions } ?: 5).apply()
        }

    /** Returns true only when an automatic merge was actually accepted. */
    fun recordsLoaded(): Boolean {
        if(closed || !enabled) return false
        val context=repository.captureAccount() ?: return false
        val owner=context.owner ?: return false
        if(!repository.isCurrent(context)) return false
        val active=operation
        if(active!=null) {
            if(repository.isCurrent(active.context)) return false
            // A merge accepted for an old account must not hold the newly current account forever.
            operation=null
        }
        if(throttled(owner)) return false
        val op=Operation(context)
        val previous=prefs.getLong(LAST+owner,Long.MIN_VALUE)
        // Reserve and stamp before invoking start so repeated or synchronous callbacks cannot start twice.
        prefs.edit().putLong(LAST+owner,clock()).apply()
        operation=op
        val accepted=start(context) { result -> complete(op,result) }
        if(!accepted) {
            if(operation===op) operation=null
            prefs.edit().apply {
                if(previous==Long.MIN_VALUE) remove(LAST+owner) else putLong(LAST+owner,previous)
            }.apply()
            return false
        }
        return true
    }

    /** A successful manual merge resets the current owner's interval without consuming the gate. */
    fun manualCompleted(context: AccountContext,result: AccountProgressMerge.Result) {
        if(closed || result.error!=null) return
        val owner=context.owner ?: return
        if(!repository.isCurrent(context)) return
        prefs.edit().putLong(LAST+owner,clock()).apply()
    }

    fun close() {
        closed=true
        operation=null
    }

    private fun throttled(owner: String): Boolean {
        val last=prefs.getLong(LAST+owner,Long.MIN_VALUE)
        if(last==Long.MIN_VALUE) return false
        val now=clock()
        // A rolled-back clock allows one attempt; its acceptance rebases the stored start time.
        if(now<last) return false
        return now-last < intervalMinutes*60_000L
    }

    private fun complete(op: Operation,result: AccountProgressMerge.Result) {
        if(Looper.myLooper()==Looper.getMainLooper()) finish(op,result)
        else main.post { finish(op,result) }
    }

    private fun finish(op: Operation,result: AccountProgressMerge.Result) {
        if(closed) { if(operation===op) operation=null; return }
        if(operation!==op) return
        operation=null
        if(repository.isCurrent(op.context)) updated(op.context,result)
    }
}
