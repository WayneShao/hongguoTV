package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Looper
import com.hongguotv.core.AccountContext
import com.hongguotv.core.AccountRepository
import com.hongguotv.core.Series
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

interface AccountIntentStorage {
    val key: String
    fun read(): String
    fun commit(value: String): Boolean
}

private class PreferenceIntentStorage(activity: Activity) : AccountIntentStorage {
    override val key = activity.packageName + ":account-intents-v1"
    private val preferences = activity.applicationContext.getSharedPreferences("account-intents-v1", 0)
    override fun read() = preferences.getString("state", "{}") ?: "{}"
    override fun commit(value: String) = preferences.edit().putString("state", value).commit()
}

/** Activity-owned intent state. No network call or disk wait is made under [lock]. */
class AccountIntents(
    activity: Activity,
    private val repo: AccountRepository,
    private val storage: AccountIntentStorage = PreferenceIntentStorage(activity)
) {
    enum class FavoriteStatus { PENDING, SUBMITTING, OUTCOME_UNKNOWN, SUBMITTED_UNCONFIRMED, CONFIRMED, FAILED }
    data class FavoriteEntry(val id: String, val added: Boolean, val revision: Long, val status: FavoriteStatus)
    private data class History(val revision: Long, val suppressed: Boolean)
    private data class Partition(
        val history: MutableMap<String, History> = linkedMapOf(),
        val favorites: MutableMap<String, FavoriteEntry> = linkedMapOf()
    )
    private class Retirement {
        val done = CountDownLatch(1)
        @Volatile var durable = false
    }
    companion object {
        private val retirementLock = Any()
        private val retired = mutableMapOf<String, MutableList<Retirement>>()
    }
    private val lock = java.lang.Object()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "account-intent-storage") }
    private val http = Executors.newSingleThreadExecutor { Thread(it, "account-intent-http") }
    private val loader = Executors.newSingleThreadExecutor { Thread(it, "account-intent-loader") }
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val partitions = linkedMapOf<String, Partition>()
    private val bindings = linkedMapOf<String, String>()
    private val resumeTimes = mutableMapOf<String, Long>()
    private var closed = false
    private var loaded = false
    private var counter = 0L
    private var accepted = 0L
    private var committed = 0L
    private var attempted = 0L
    private var pending: Pair<Long, String>? = null
    private var draining = false

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { listeners.forEach { runCatching { it() } } }
    fun isReady(): Boolean = synchronized(lock) { loaded && !closed }

    /** Callback runs on the loader, never via a main-thread rendezvous. */
    fun loadAsync(callback: (Boolean) -> Unit) {
        synchronized(lock) {
            if (closed) return
            loader.execute {
                val barriers = synchronized(retirementLock) { retired[storage.key]?.toList().orEmpty() }
                var okay = true
                for (barrier in barriers) {
                    barrier.done.await()
                    if (!barrier.durable) okay = false
                }
                if (okay) {
                    okay = runCatching {
                        val raw = storage.read()
                        synchronized(lock) {
                            if (!closed && !loaded) {
                                decode(raw)
                                loaded = true
                                // An interrupted submission is ambiguous, never a new POST.
                                var converted = false
                                partitions.values.forEach { p -> p.favorites.toMap().forEach { (id, entry) ->
                                    if (entry.status == FavoriteStatus.SUBMITTING) {
                                        p.favorites[id] = entry.copy(status = FavoriteStatus.OUTCOME_UNKNOWN)
                                        converted = true
                                    }
                                } }
                                if (converted) persistLocked()
                            }
                        }
                        isReady()
                    }.getOrDefault(false)
                }
                callback(okay)
                changed()
            }
        }
    }

    private fun usable(context: AccountContext) = loaded && !closed && repo.isCurrent(context)
    private fun owner(context: AccountContext) = context.owner ?: "provisional:${context.credentialOwner}"
    /** Only the exact credential hash may promote a provisional record. */
    private fun partition(context: AccountContext): Partition {
        val target = partitions.getOrPut(owner(context)) { Partition() }
        if (context.owner != null) {
            val provisional = partitions.remove("provisional:${context.credentialOwner}")
            if (provisional != null) {
                provisional.history.forEach { (id, value) ->
                    if (value.revision > (target.history[id]?.revision ?: -1)) target.history[id] = value
                }
                provisional.favorites.forEach { (id, value) ->
                    if (value.revision > (target.favorites[id]?.revision ?: -1)) target.favorites[id] = value
                }
                persistLocked()
            }
        }
        return target
    }

    fun bindHistory(context: AccountContext): Boolean = synchronized(lock) {
        if (!usable(context)) return@synchronized false
        val canonical = context.owner ?: return@synchronized false
        partition(context)
        val previous = bindings[context.legacyOwner]
        if (previous != null) return@synchronized previous == canonical
        bindings[context.legacyOwner] = canonical
        persistLocked()
        true
    }

    fun revision(context: AccountContext): Long = synchronized(lock) {
        if (!usable(context)) -1L else { partition(context); counter }
    }

    fun deleteHistory(context: AccountContext, ids: Set<String>): Long {
        val result = synchronized(lock) {
            if (!usable(context)) return@synchronized -1L
            val p = partition(context)
            val revision = ++counter
            ids.forEach { p.history[it] = History(revision, true) }
            persistLocked()
            revision
        }
        changed()
        return result
    }

    fun rewatch(context: AccountContext, id: String): Long {
        val result = synchronized(lock) {
            if (!usable(context)) return@synchronized -1L
            val p = partition(context)
            val revision = ++counter
            p.history[id] = History(revision, false)
            persistLocked()
            revision
        }
        changed()
        return result
    }

    fun suppressHistory(context: AccountContext, id: String): Boolean = synchronized(lock) {
        !usable(context) || partition(context).history[id]?.suppressed == true
    }

    fun historyAllowed(context: AccountContext, id: String, revision: Long): Boolean = synchronized(lock) {
        if (!usable(context) || revision < 0) return@synchronized false
        val history = partition(context).history[id]
        history?.suppressed != true && (history?.revision ?: 0L) == revision
    }
    fun historyRevision(context: AccountContext,id: String): Long = synchronized(lock) {
        if(!usable(context)) -1L else partition(context).history[id]?.revision ?: 0L
    }

    /** Off-main only; a true result proves the allowed revision has reached disk. */
    fun durableHistory(context: AccountContext, id: String, revision: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Durability waits must not run on main" }
        val serial = synchronized(lock) {
            if (!historyAllowed(context, id, revision)) return false
            accepted
        }
        return awaitDurable(serial) && historyAllowed(context, id, revision)
    }

    fun suppressFavorite(context: AccountContext, id: String): Boolean = synchronized(lock) {
        !usable(context) || partition(context).favorites[id]?.added == false
    }
    fun favoriteStatus(context: AccountContext, id: String): FavoriteStatus? = synchronized(lock) {
        if (!usable(context)) null else partition(context).favorites[id]?.status
    }
    fun favoriteEntries(context: AccountContext): List<FavoriteEntry> = synchronized(lock) {
        if (!usable(context)) emptyList() else partition(context).favorites.values.toList()
    }

    fun setFavorite(context: AccountContext, series: Series, added: Boolean): Long = setFavorite(context, series.id, added)
    fun setFavorite(context: AccountContext, id: String, added: Boolean): Long {
        val revision = synchronized(lock) {
            if (!usable(context)) return@synchronized -1L
            val p = partition(context)
            val entry = FavoriteEntry(id, added, ++counter, FavoriteStatus.PENDING)
            p.favorites[id] = entry
            persistLocked()
            if (context.owner != null) http.execute { submit(context, entry) }
            entry.revision
        }
        changed()
        return revision
    }
    fun retryFavorite(context: AccountContext, id: String): Long {
        // Read and accept under the same lock, so retry cannot supersede a newer opposite action.
        return synchronized(lock) {
            if (!usable(context)) return@synchronized -1L
            val entry = partition(context).favorites[id] ?: return@synchronized -1L
            setFavorite(context, id, entry.added)
        }
    }

    private fun current(context: AccountContext, entry: FavoriteEntry): Boolean =
        usable(context) && partition(context).favorites[entry.id]?.revision == entry.revision

    private fun status(context: AccountContext, entry: FavoriteEntry, status: FavoriteStatus): Long? {
        val serial = synchronized(lock) {
            if (!current(context, entry)) return@synchronized null
            partition(context).favorites[entry.id] = entry.copy(status = status)
            persistLocked()
        }
        if (serial != null) changed()
        return serial
    }

    private fun submit(context: AccountContext, entry: FavoriteEntry) {
        val serial = status(context, entry, FavoriteStatus.SUBMITTING) ?: return
        if (!awaitDurable(serial)) {
            status(context, entry, FavoriteStatus.FAILED)
            return
        }
        if (!synchronized(lock) { current(context, entry) }) return
        val acknowledged = runCatching { repo.writeFavorite(context, entry.id, entry.added) }.isSuccess
        if (!acknowledged) {
            if (status(context, entry, FavoriteStatus.OUTCOME_UNKNOWN) != null) reconcile(context, entry)
            return
        }
        if (status(context, entry, FavoriteStatus.SUBMITTED_UNCONFIRMED) == null) return
        reconcile(context, entry)
    }

    /** Drain only definitely unsubmitted work after login/owner resolution. Unlike resume,
     * this explicitly permits writes. SUBMITTING/unknown work is never selected. */
    fun syncPending(context: AccountContext) {
        synchronized(lock) {
            if (!usable(context) || context.owner == null) return
            partition(context).favorites.values.filter { it.status == FavoriteStatus.PENDING }.forEach { entry ->
                http.execute {
                    val pending = synchronized(lock) {
                        current(context, entry) && partition(context).favorites[entry.id]?.status == FavoriteStatus.PENDING
                    }
                    if (pending) submit(context, entry)
                }
            }
        }
    }

    private fun reconcile(context: AccountContext, entry: FavoriteEntry) {
        if (!synchronized(lock) { current(context, entry) }) return
        val membership = runCatching { repo.readFavoriteMembership(context) }.getOrNull() ?: return
        if ((entry.added && entry.id in membership.ids) ||
            (!entry.added && membership.complete && entry.id !in membership.ids)) {
            status(context, entry, FavoriteStatus.CONFIRMED)
        }
    }

    /** Read-only and bounded: at most one membership read per resume window. */
    fun resume(context: AccountContext) {
        synchronized(lock) {
            if (!usable(context) || context.owner == null) return
            val now = System.nanoTime()
            val key = owner(context)
            val last = resumeTimes[key]
            if (last != null && now - last < 30_000_000_000L) return
            resumeTimes[key] = now
            val entries = partition(context).favorites.values.filter { it.status != FavoriteStatus.CONFIRMED }.toList()
            if (entries.isEmpty()) return
            http.execute {
                if (!synchronized(lock) { usable(context) }) return@execute
                val membership = runCatching { repo.readFavoriteMembership(context) }.getOrNull() ?: return@execute
                entries.forEach { entry ->
                    if ((entry.added && entry.id in membership.ids) ||
                        (!entry.added && membership.complete && entry.id !in membership.ids))
                        status(context, entry, FavoriteStatus.CONFIRMED)
                }
            }
        }
    }

    /** Capture immutable serialized snapshots in acceptance order, coalesce only pending writes. */
    private fun persistLocked(): Long {
        val serial = ++accepted
        pending = serial to encode()
        if (!draining) {
            draining = true
            writer.execute { drain() }
        }
        return serial
    }
    private fun drain() {
        while (true) {
            val snapshot = synchronized(lock) {
                val next = pending
                if (next == null) { draining = false; lock.notifyAll(); return }
                pending = null
                next
            }
            val success = runCatching { storage.commit(snapshot.second) }.getOrDefault(false)
            synchronized(lock) {
                attempted = snapshot.first
                if (success) committed = snapshot.first
                lock.notifyAll()
            }
        }
    }
    private fun awaitDurable(serial: Long): Boolean = synchronized(lock) {
        while (committed < serial && attempted < serial && !closed) lock.wait()
        !closed && committed >= serial
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val barrier = Retirement()
            synchronized(retirementLock) { retired.getOrPut(storage.key) { mutableListOf() }.add(barrier) }
            // This task is ordered after the independent persistence drain, never after HTTP.
            writer.execute {
                synchronized(lock) { barrier.durable = committed >= accepted }
                barrier.done.countDown()
            }
            writer.shutdown()
            http.shutdownNow()
            loader.shutdown()
            listeners.clear()
            lock.notifyAll()
        }
    }

    private fun encode(): String {
        val root = JSONObject().put("revision", counter)
        val owners = JSONObject()
        partitions.forEach { (owner, p) ->
            val history = JSONObject()
            p.history.forEach { (id, h) -> history.put(id, JSONObject().put("revision", h.revision).put("suppressed", h.suppressed)) }
            val favorites = JSONObject()
            p.favorites.forEach { (id, f) -> favorites.put(id, JSONObject().put("revision", f.revision).put("added", f.added).put("status", f.status.name)) }
            owners.put(owner, JSONObject().put("history", history).put("favorites", favorites))
        }
        val legacy = JSONObject()
        bindings.forEach { (key, value) -> legacy.put(key, value) }
        return root.put("owners", owners).put("bindings", legacy).toString()
    }
    private fun decode(raw: String) {
        val root = JSONObject(raw)
        counter = root.optLong("revision", 0)
        val owners = root.optJSONObject("owners") ?: JSONObject()
        owners.keys().forEach { owner ->
            val value = owners.getJSONObject(owner)
            val p = Partition()
            val history = value.optJSONObject("history") ?: JSONObject()
            history.keys().forEach { id ->
                val h = history.getJSONObject(id)
                p.history[id] = History(h.getLong("revision"), h.getBoolean("suppressed"))
            }
            val favorites = value.optJSONObject("favorites") ?: JSONObject()
            favorites.keys().forEach { id ->
                val f = favorites.getJSONObject(id)
                p.favorites[id] = FavoriteEntry(id, f.getBoolean("added"), f.getLong("revision"), FavoriteStatus.valueOf(f.getString("status")))
            }
            partitions[owner] = p
        }
        val legacy = root.optJSONObject("bindings") ?: JSONObject()
        legacy.keys().forEach { bindings[it] = legacy.getString(it) }
    }
}
