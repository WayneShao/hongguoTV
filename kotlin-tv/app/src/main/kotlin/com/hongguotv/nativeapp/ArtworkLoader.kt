// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.lang.ref.WeakReference
import java.util.PriorityQueue
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** Page-owned artwork requests. Public methods are called on the UI thread; decoding stays on workers. */
class ArtworkLoader(context: Context) {
    companion object {
        const val PRIORITY_FOCUSED = 0
        const val PRIORITY_VISIBLE = 1
        const val PRIORITY_PREFETCH = 2
        private const val MAX_PENDING = 24
        private const val MAX_WARM_BYTES = 4 * 1024 * 1024
    }

    private data class Key(val url: String, val width: Int, val height: Int)
    private class Binding(view: ImageView, val owner: Long, val url: String, var priority: Int,
                          val width: Int, val height: Int) {
        val view = WeakReference(view)
        var key: Key? = null
        var applied: Key? = null
        var failed: Key? = null
        lateinit var layout: View.OnLayoutChangeListener
        lateinit var attach: View.OnAttachStateChangeListener
    }
    private class Job(val key: Key, val owner: Long, var priority: Int, val order: Long) {
        val listeners = linkedSetOf<Binding>()
        @Volatile var cancelled = false
        @Volatile var call: Call? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val lowRam = context.applicationContext.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    private val cacheBytes = (if (lowRam) 4 else 8) * 1024 * 1024
    private val cache = object : LruCache<Key, Bitmap>(cacheBytes) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }
    private val disk = ArtworkCache(File(context.applicationContext.cacheDir, "artwork-v1"))
    // This client owns its dispatcher and pool; cancelling artwork never cancels video or catalog calls.
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val fetcher = ArtworkFetcher(disk, http, AndroidArtworkDecoder(lowRam))
    private val workers = Executors.newFixedThreadPool(if (lowRam) 1 else 2) { runnable ->
        Thread(runnable, "hongguotv-artwork").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val signal = Object()
    private val pending = PriorityQueue<Job>(compareBy<Job> { it.priority }.thenBy { it.order })
    private val jobs = mutableMapOf<Key, Job>()
    private val bindings = WeakHashMap<ImageView, Binding>()
    private var owner = 0L
    private var order = 0L
    @Volatile private var closed = false

    init { repeat(if (lowRam) 1 else 2) { workers.execute(::work) } }

    /** Size defaults to the laid-out content bounds, including the detail page's portrait artwork. */
    fun load(view: ImageView, url: String, priority: Int = PRIORITY_VISIBLE, widthPx: Int = 0, heightPx: Int = 0) {
        if (closed) return
        val previous = bindings[view]
        if (previous != null && previous.owner == owner && previous.url == url && previous.width == widthPx && previous.height == heightPx) {
            previous.priority = priority.coerceIn(PRIORITY_FOCUSED, PRIORITY_PREFETCH)
            previous.failed = null
            request(previous)
            return
        }
        if (previous != null) unbind(previous)
        view.setImageDrawable(null)
        if (url.isBlank()) return
        val binding = Binding(view, owner, url, priority.coerceIn(PRIORITY_FOCUSED, PRIORITY_PREFETCH), widthPx, heightPx)
        binding.layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> request(binding) }
        binding.attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { request(binding) }
            override fun onViewDetachedFromWindow(v: View) { unbind(binding) }
        }
        bindings[view] = binding
        view.addOnLayoutChangeListener(binding.layout)
        view.addOnAttachStateChangeListener(binding.attach)
        request(binding)
    }

    private fun request(binding: Binding) {
        val view = binding.view.get() ?: return
        if (closed || binding.owner != owner || bindings[view] !== binding) return
        val width = if (binding.width > 0) binding.width else view.width - view.paddingLeft - view.paddingRight
        val height = if (binding.height > 0) binding.height else view.height - view.paddingTop - view.paddingBottom
        if (width <= 0 || height <= 0) return
        // A malformed layout cannot ask an old TV to allocate a full-screen, multi-megapixel thumbnail.
        val scale = min(1.0, 1024.0 / max(width, height))
        val key = Key(binding.url, max(1, (width * scale).toInt()), max(1, (height * scale).toInt()))
        if (binding.key != key) { detachJob(binding); binding.key = key; binding.failed = null }
        if (binding.applied == key || binding.failed == key) return
        cache.get(key)?.let { bitmap -> view.setImageBitmap(bitmap); binding.applied = key; return }
        var cancelled: Job? = null
        synchronized(signal) {
            jobs[key]?.let { job ->
                job.listeners += binding
                if (binding.priority < job.priority) {
                    val queued = pending.remove(job)
                    job.priority = binding.priority
                    if (queued) pending.add(job)
                }
                return
            }
            if (pending.size >= MAX_PENDING) {
                val worst = pending.maxWithOrNull(compareBy<Job> { it.priority }.thenBy { -it.order })!!
                if (worst.priority < binding.priority) return
                cancelLocked(worst)
                cancelled = worst
            }
            val job = Job(key, owner, binding.priority, ++order)
            job.listeners += binding
            jobs[key] = job
            pending += job
            signal.notifyAll()
        }
        cancelled?.call?.cancel()
    }

    private fun cancelLocked(job: Job) {
        job.cancelled = true
        pending.remove(job)
        if (jobs[job.key] === job) jobs.remove(job.key)
        job.listeners.clear()
    }

    private fun detachJob(binding: Binding) {
        val key = binding.key ?: return
        val cancelled = synchronized(signal) {
            jobs[key]?.takeIf { it.owner == binding.owner }?.let { job ->
                job.listeners.remove(binding)
                if (job.listeners.isEmpty()) { cancelLocked(job); job } else null
            }
        }
        cancelled?.call?.cancel()
    }

    private fun unbind(binding: Binding) {
        binding.view.get()?.let { view ->
            view.removeOnLayoutChangeListener(binding.layout)
            view.removeOnAttachStateChangeListener(binding.attach)
            if (bindings[view] === binding) bindings.remove(view)
        }
        detachJob(binding)
        binding.view.clear()
    }

    /** Disk-only warming on the gradual worker: no bitmap allocation on startup. */
    internal fun warm(url: String) {
        if (closed || !url.startsWith("https://") || disk.get(url) != null) return
        synchronized(signal) { if (jobs.isNotEmpty()) return }
        com.hongguotv.core.RequestScope.execute(http.newCall(Request.Builder().url(url).build())) { response ->
            if (!response.isSuccessful) return@execute
            val body = response.body ?: return@execute
            if (body.contentLength() > MAX_WARM_BYTES) return@execute
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                while (true) {
                    com.hongguotv.core.RequestScope.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (bytes.size() + n > MAX_WARM_BYTES) return@execute
                    bytes.write(buffer, 0, n)
                }
            }
            com.hongguotv.core.RequestScope.ensureActive()
            if (bytes.size() > 0) disk.put(url, bytes.toByteArray())
        }
    }

    /** Release every old-page listener and bitmap binding, including queued and in-flight requests. */
    fun cancelPage(clearMemory: Boolean = false) {
        owner++
        val old = bindings.values.toList()
        old.forEach { binding -> binding.view.get()?.setImageDrawable(null); unbind(binding) }
        val cancelled = synchronized(signal) {
            jobs.values.toList().also { list -> list.forEach(::cancelLocked); signal.notifyAll() }
        }
        cancelled.forEach { it.call?.cancel() }
        if (clearMemory) cache.evictAll()
    }

    fun cancelAll(clearMemory: Boolean = false) = cancelPage(clearMemory)

    @Suppress("DEPRECATION") // Android 8 devices still report the running-memory pressure levels.
    fun trimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> cache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> cache.trimToSize(cacheBytes / 2)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        cancelPage(clearMemory = true)
        synchronized(signal) { signal.notifyAll() }
        workers.shutdownNow()
        // TLS socket pool disposal can block and must not happen on the UI thread.
        Thread({
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }, "hongguotv-artwork-cleanup").start()
    }

    private fun work() {
        while (!closed) {
            val job = try {
                synchronized(signal) {
                    while (pending.isEmpty() && !closed) signal.wait()
                    if (closed) null else pending.poll()
                }
            } catch (_: InterruptedException) { return } ?: return
            val bitmap = try {
                if (job.cancelled) null else cache.get(job.key) ?: fetch(job)
            } catch (_: Exception) { null } catch (_: OutOfMemoryError) { cache.evictAll(); null }
            val listeners = synchronized(signal) {
                if (jobs[job.key] === job) jobs.remove(job.key)
                if (!job.cancelled && !closed && bitmap != null) cache.put(job.key, bitmap)
                job.listeners.toList().also { job.listeners.clear() }
            }
            if (!job.cancelled && !closed) main.post {
                if (job.owner == owner && !closed) {
                    listeners.forEach { binding ->
                        val view = binding.view.get()
                        if (view != null && bindings[view] === binding && binding.key == job.key) {
                            if (bitmap != null) { view.setImageBitmap(bitmap); binding.applied = job.key }
                            else binding.failed = job.key
                        }
                    }
                    refillVisible()
                }
            }
        }
    }

    // Dropped preloads must not leave a visible card blank when a queue slot becomes free.
    private fun refillVisible() {
        val visible = Rect()
        bindings.values.toList().filter { binding ->
            binding.applied != binding.key && binding.failed != binding.key && binding.view.get()?.getGlobalVisibleRect(visible) == true
        }.sortedBy { it.priority }.take(MAX_PENDING).forEach(::request)
    }

    private fun fetch(job: Job): Bitmap? =
        fetcher.fetch(job.key.url, job.key.width, job.key.height, { job.cancelled }, { job.call = it })
}
