// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

// 远程视频资源处理：管理播放数据读取与资源关闭。

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(private val http: OkHttpClient, val info: StreamInfo,
    private val traffic: PreloadGate?=null, background: Boolean=false) : Closeable {
    @Volatile private var background=background
    private val preempted=java.util.concurrent.atomic.AtomicBoolean(false)
    private val delivered=java.util.concurrent.atomic.AtomicLong()
    val deliveredBytes get()=delivered.get()
    val reusable get()=!closed
    fun promote() {background=false}
    fun cancelReads() { synchronized(calls) {calls.toList().forEach {it.cancel()}} }
    fun yieldToForeground() { if(background) {preempted.set(true);cancelReads()} }
    private val streamingHttp=http.newBuilder().callTimeout(0,java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(minOf(http.readTimeoutMillis.takeIf {it>0} ?: 15000,15000).toLong(),java.util.concurrent.TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false).build()
    private inline fun <T> demand(block: ()->T): T {
        val active=!background
        if(active) traffic?.networkStarted()
        try {return block()} finally {if(active) traffic?.networkFinished()}
    }

    @Volatile private var closed = false
    private val calls = mutableSetOf<Call>()
    private val diskLock=Any()
    private var cachedFile: java.io.File?=null
    @Volatile private var diskBytes=0L
    fun durationMs(): Long? = if(::prepared.isInitialized) Mp4Duration.read(prepared.header) else null
    fun cachedSize(): Long = maxOf(prefix.size.toLong(),diskBytes)
    /** Cache original encrypted bytes in disposable app cache, never a second decoded video. */
    fun preload(directory: java.io.File, claimed: ()->Boolean, budget: Long = 64L*1024*1024, onFailure: (Exception)->Boolean={false}): RemoteVideo {
        prepare()
        if(total>minOf(budget,64L*1024*1024) || claimed()) return this
        directory.mkdirs()
        if(total>directory.usableSpace-16L*1024*1024) return this
        val file=cachedFile ?: java.io.File.createTempFile("hongguo-prefetch-", ".part", directory)
        synchronized(diskLock) {
            if(closed) { file.delete(); throw IOException("播放请求已取消") }
            if(cachedFile==null) {
                cachedFile=file
                java.io.RandomAccessFile(file,"rw").use { it.write(prefix) }
                diskBytes=prefix.size.toLong()
            }
        }
        try {
            while(diskBytes<total && !claimed()) {
                val start=diskBytes
                val bytes=try {
                    preempted.set(false)
                    range(start,minOf(total-1,start+256*1024-1)).bytes
                } catch(e: Exception) {
                    RequestScope.ensureActive()
                    if(closed) throw e
                    if(preempted.getAndSet(false)) continue
                    if(e is VideoResourceChangedException) throw e
                    if(onFailure(e)) continue
                    return this // Keep verified bytes; foreground can continue the remainder.
                }
                synchronized(diskLock) {
                    if(closed) throw IOException("播放请求已取消")
                    java.io.RandomAccessFile(file,"rw").use { it.seek(start); it.write(bytes) }
                    diskBytes+=bytes.size
                }
                traffic?.chunkFinished()
            }
            return this
        } catch(e: Exception) { close(); throw e }
    }


    data class Range(val bytes: ByteArray, val total: Long)

    var total: Long = 0
        private set

    private lateinit var prepared: MediaCrypto.Prepared
    @Volatile private var prefix = byteArrayOf()

    private fun newCall(start: Long, end: Long, streaming: Boolean=false): Call {
        if (closed) throw IOException("播放请求已取消")
        require(start >= 0 && end >= start && end - start < 8 * 1024 * 1024)
        val request =
            Request.Builder()
                .url(info.url)
                .header("User-Agent", VendorConstants.VIDEO_UA)
                .header("Referer", "https://novel.snssdk.com/")
                .header("Range", "bytes=$start-$end")
                .header("Accept-Encoding", "identity")
                .build()
        val call = streamingHttp.newCall(request)
        // A player may stop consuming a live body when its sample buffer is full.
        // Only eager/background fetches get a total deadline; foreground streaming uses idle read timeout.
        if(streaming) call.timeout().clearTimeout()
        else call.timeout().timeout(if(background) 18 else 30, java.util.concurrent.TimeUnit.SECONDS)
        synchronized(calls) {
            if (closed) throw IOException("播放请求已取消")
            calls += call
        }
        return call
    }

    private fun rangeSize(response: Response, start: Long, end: Long): Pair<Int, Long> {
        if (response.code != 206) throw VideoHttpException(response.code,response.header("Retry-After")?.toLongOrNull())
        val match =
            Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                .matchEntire(response.header("Content-Range").orEmpty())
                ?: throw IOException("视频分段响应异常")
        val (begin, finish, size) = match.destructured
        val full = size.toLong()
        val last = minOf(end, full - 1)
        if (begin.toLong() != start || finish.toLong() != last || full <= start)
            throw IOException("视频分段范围不匹配")
        if (total != 0L && full != total) throw VideoResourceChangedException()
        return (last - start + 1).toInt() to full
    }

    private fun range(start: Long, end: Long): Range {
        val call = newCall(start, end)
        try {
            return demand { RequestScope.execute(call) { response ->
                val (n, full) = rangeSize(response, start, end)
                val body = response.body ?: throw IOException("视频分段为空")
                val bytes = body.source().readByteArray(n.toLong())
                Range(bytes, full)
            } }
        } finally {
            synchronized(calls) { calls -= call }
        }
    }

    /**
     * Consume the index progressively within one request. Avoid a second round trip when moov ends
     * after the initial probe but inside the existing 256 KiB range.
     */
    private fun indexPrefix(initialBytes: Int): Range {
        val call = newCall(0, 256 * 1024 - 1)
        try {
            return demand { RequestScope.execute(call) { response ->
                val (size, full) = rangeSize(response, 0, 256 * 1024 - 1)
                val source = (response.body ?: throw IOException("视频分段为空")).source()
                val first = source.readByteArray(minOf(initialBytes, size).toLong())
                val needed = minOf(MediaCrypto.headerEnd(first, full), size)
                val bytes =
                    if (needed > first.size)
                        first + source.readByteArray((needed - first.size).toLong())
                    else first
                Range(bytes, full)
            } }
        } finally {
            synchronized(calls) { calls -= call }
        }
    }

    /**
     * One bounded HTTP range, delivered as bytes arrive instead of waiting for the whole body. The
     * playback data source owns this reader; close() interrupts an outstanding body read.
     */
    fun openRead(start: Long, length: Int): InputStream {
        check(::prepared.isInitialized)
        require(start in 0 until total && length in 1..1024 * 1024)
        val end = minOf(total - 1, start + length - 1)
        val call = newCall(start, end,streaming=true)
        var response: Response? = null
        try {
            val opened = demand { call.execute() }
            response = opened
            val (size, _) = rangeSize(opened, start, end)
            val input = (opened.body ?: throw IOException("视频分段为空")).byteStream()
            return object : InputStream() {
                private var position = start
                private var remaining = size
                private var readerClosed = false
                private val scratch = ByteArray(32 * 1024)

                override fun read(): Int {
                    val single = ByteArray(1)
                    return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
                }

                override fun read(target: ByteArray, offset: Int, count: Int): Int {
                    require(offset >= 0 && count >= 0 && offset <= target.size - count)
                    if (count == 0) return 0
                    if (readerClosed || closed) throw IOException("播放请求已取消")
                    if (remaining == 0) return -1
                    val n = demand { input.read(scratch, 0, minOf(count, remaining, scratch.size)) }
                    if (n < 0) throw java.io.EOFException("视频分段意外结束")
                    MediaCrypto.decrypt(scratch.copyOf(n), position, info.key, prepared)
                        .copyInto(target, offset)
                    delivered.addAndGet(n.toLong())
                    position += n
                    remaining -= n
                    return n
                }

                override fun close() {
                    if (readerClosed) return
                    readerClosed = true
                    if (remaining > 0) call.cancel()
                    opened.close()
                    synchronized(calls) { calls -= call }
                }
            }
        } catch (error: Exception) {
            call.cancel()
            response?.close()
            synchronized(calls) { calls -= call }
            throw error
        }
    }

    @JvmOverloads
    @Synchronized
    fun prepare(initialBytes: Int = 32 * 1024): RemoteVideo {
        require(initialBytes in 16 * 1024..256 * 1024)
        if (::prepared.isInitialized) return this
        val first =
            if (initialBytes < 256 * 1024) indexPrefix(initialBytes) else range(0, 256 * 1024 - 1)
        total = first.total
        prefix = first.bytes
        val end = MediaCrypto.headerEnd(first.bytes, total)
        val header =
            if (end > first.bytes.size)
                first.bytes + range(first.bytes.size.toLong(), end - 1L).bytes
            else first.bytes.copyOf(end)
        if (header.size > prefix.size) prefix = header
        prepared = MediaCrypto.prepare(header, info.key)
        if (prepared.samples.any { it.end > total }) throw IOException("视频样本超出资源大小")
        return this
    }

    /** Bounded warm-up. Retains raw bytes so decryption still uses the absolute sample offset. */
    @Synchronized
    fun warm(bytes: Int = 1024 * 1024): RemoteVideo {
        require(bytes in 256 * 1024..4 * 1024 * 1024)
        prepare()
        val size = minOf(total, bytes.toLong()).toInt()
        if (prefix.size < size) prefix = prefix + range(prefix.size.toLong(), size - 1L).bytes
        return this
    }

    /** Bytes already held locally; a player can consume these before another network range. */
    fun retainedBytes(): Long = prefix.size.toLong() + if(::prepared.isInitialized) prepared.header.size.toLong() + prepared.samples.size * 64L else 0L

    fun cachedBytes(start: Long): Int =
        (cachedSize() - start).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    fun read(start: Long, length: Int): ByteArray {
        require(length in 1..1024 * 1024 && start >= 0)
        if (closed) throw IOException("播放请求已取消")
        if (start >= total) return byteArrayOf()
        val end = minOf(total, start + length)
        val first=synchronized(diskLock) {
            val file=cachedFile
            val available=(minOf(end,cachedSize())-start).coerceAtLeast(0).toInt()
            if(available<=0) byteArrayOf()
            else if(file!=null && start+available<=diskBytes) ByteArray(available).also { bytes ->
                java.io.RandomAccessFile(file,"r").use { it.seek(start); it.readFully(bytes) }
            } else prefix.copyOfRange(start.toInt(),start.toInt()+available)
        }
        val available=first.size
        val raw =
            if (start + available < end) first + range(start + available, end - 1).bytes else first
        return MediaCrypto.decrypt(raw, start, info.key, prepared).also { delivered.addAndGet(it.size.toLong()) }
    }

    override fun close() {
        synchronized(calls) {
            closed = true
            calls.toList().forEach { it.cancel() }
            calls.clear()
        }
        synchronized(diskLock) { cachedFile?.delete(); cachedFile=null; diskBytes=0 }
    }
}
