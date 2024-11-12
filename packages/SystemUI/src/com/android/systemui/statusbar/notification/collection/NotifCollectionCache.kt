/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.collection

import android.annotation.SuppressLint
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printCollection
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.time.SystemClockImpl
import com.android.systemui.util.withIncreasedIndent
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A cache in which entries can "survive" getting purged [retainCount] times, given consecutive
 * [purge] calls made at least [purgeTimeoutMillis] apart. See also [purge].
 *
 * This cache is safe for multithreaded usage, and is recommended for objects that take a while to
 * resolve (such as drawables, or things that require binder calls). As such, [getOrFetch] is
 * recommended to be run on a background thread, while [purge] can be done from any thread.
 *
 * Important: This cache does NOT have a maximum size, cleaning it up (via [purge]) is the
 * responsibility of the caller, to avoid keeping things in memory unnecessarily.
 */
@SuppressLint("DumpableNotRegistered") // this will be dumped by container classes
class NotifCollectionCache<V>(
    private val retainCount: Int = 1,
    private val purgeTimeoutMillis: Long = 1000L,
    private val systemClock: SystemClock = SystemClockImpl(),
) : Dumpable {
    @get:VisibleForTesting val cache = ConcurrentHashMap<String, CacheEntry>()

    // Counters for cache hits and misses to be used to calculate and dump the hit ratio
    @get:VisibleForTesting val misses = AtomicInteger(0)
    @get:VisibleForTesting val hits = AtomicInteger(0)

    init {
        if (retainCount < 0) {
            throw IllegalArgumentException("retainCount cannot be negative")
        }
    }

    inner class CacheEntry(val key: String, val value: V) {
        /**
         * The "lives" represent how many times the entry will remain in the cache when purging it
         * is attempted.
         */
        @get:VisibleForTesting var lives: Int = retainCount + 1
        /**
         * The last time this entry lost a "life". Starts at a negative value chosen so that the
         * first purge is always considered "valid".
         */
        private var lastValidPurge: Long = -purgeTimeoutMillis

        fun resetLives() {
            // Lives/timeouts don't matter if retainCount is 0
            if (retainCount == 0) {
                return
            }

            synchronized(key) {
                lives = retainCount + 1
                lastValidPurge = -purgeTimeoutMillis
            }
            // Add it to the cache again just in case it was deleted before we could reset the lives
            cache[key] = this
        }

        fun tryPurge(): Boolean {
            // Lives/timeouts don't matter if retainCount is 0
            if (retainCount == 0) {
                return true
            }

            // Using uptimeMillis since it's guaranteed to be monotonic, as we don't want a
            // timezone/clock change to break us
            val now = systemClock.uptimeMillis()

            // Cannot purge the same entry from two threads simultaneously
            synchronized(key) {
                if (now - lastValidPurge < purgeTimeoutMillis) {
                    return false
                }
                lastValidPurge = now
                return --lives <= 0
            }
        }

        override fun toString(): String {
            return "$key = $value"
        }
    }

    /**
     * Get value from cache, or fetch it and add it to cache if not found. This can be called from
     * any thread, but is usually expected to be called from the background.
     *
     * @param key key for the object to be obtained
     * @param fetch method to fetch the object and add it to the cache if not present; note that
     *   there is no guarantee that two [fetch] cannot run in parallel for the same [key] (if
     *   [getOrFetch] is called simultaneously from different threads), so be mindful of potential
     *   side effects
     */
    fun getOrFetch(key: String, fetch: (String) -> V): V {
        val entry = cache[key]
        if (entry != null) {
            hits.incrementAndGet()
            // Refresh lives on access
            entry.resetLives()
            return entry.value
        }

        misses.incrementAndGet()
        val value = fetch(key)
        cache[key] = CacheEntry(key, value)
        return value
    }

    /**
     * Clear entries that are NOT in [wantedKeys] if appropriate. This can be called from any
     * thread.
     *
     * If retainCount > 0, a given entry will need to not be present in [wantedKeys] for
     * ([retainCount] + 1) consecutive [purge] calls made within at least [purgeTimeoutMillis] of
     * each other in order to be cleared. This count will be reset for any given entry 1) if
     * [getOrFetch] is called for the entry or 2) if the entry is present in [wantedKeys] in a
     * subsequent [purge] call. We prioritize keeping the entry if possible, so if [purge] is called
     * simultaneously with [getOrFetch] on different threads for example, we will try to keep it in
     * the cache, although it is not guaranteed. If avoiding cache misses is a concern, consider
     * increasing the [retainCount] or [purgeTimeoutMillis].
     *
     * For example, say [retainCount] = 1 and [purgeTimeoutMillis] = 1000 and we start with entries
     * (a, b, c) in the cache:
     * ```kotlin
     * purge((a, c)); // marks b for deletion
     * Thread.sleep(500)
     * purge((a, c)); // does nothing as it was called earlier than the min 1s
     * Thread.sleep(500)
     * purge((b, c)); // b is no longer marked for deletion, but now a is
     * Thread.sleep(1000);
     * purge((c));    // deletes a from the cache and marks b for deletion, etc.
     * ```
     */
    fun purge(wantedKeys: Collection<String>) {
        for ((key, entry) in cache) {
            if (key in wantedKeys) {
                entry.resetLives()
            } else if (entry.tryPurge()) {
                cache.remove(key)
            }
        }
    }

    /** Clear all entries from the cache. */
    fun clear() {
        cache.clear()
    }

    override fun dump(pwOrig: PrintWriter, args: Array<out String>) {
        val pw = pwOrig.asIndenting()

        pw.println("$TAG(retainCount = $retainCount, purgeTimeoutMillis = $purgeTimeoutMillis)")
        pw.withIncreasedIndent {
            pw.printCollection(
                "entries present in cache",
                cache.values.stream().map { it.toString() }.sorted().toList(),
            )

            val misses = misses.get()
            val hits = hits.get()
            pw.println(
                "cache hit ratio = ${(hits.toFloat() / (hits + misses)) * 100}% " +
                    "($hits hits, $misses misses)"
            )
        }
    }

    companion object {
        const val TAG = "NotifCollectionCache"
    }
}
