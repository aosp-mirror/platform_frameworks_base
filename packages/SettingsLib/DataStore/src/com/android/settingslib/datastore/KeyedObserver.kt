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

package com.android.settingslib.datastore

import androidx.annotation.AnyThread
import androidx.annotation.GuardedBy
import androidx.collection.MutableScatterMap
import java.util.WeakHashMap
import java.util.concurrent.Executor

/**
 * Callback to be informed of changes in [KeyedObservable] object.
 *
 * The observer is weakly referenced, a strong reference must be kept.
 */
fun interface KeyedObserver<in K> {
    /**
     * Called by [KeyedObservable] in the event of changes.
     *
     * This callback will run in the given [Executor] when observer is added.
     *
     * @param key key that has been changed
     * @param reason the reason of change
     * @see KeyedObservable.addObserver
     */
    fun onKeyChanged(key: K, reason: Int)
}

/**
 * A key-value observable object allows to observe change with [KeyedObserver].
 *
 * Notes:
 * - The order in which observers will be notified is unspecified.
 * - The observer is weakly referenced to avoid memory leaking, the call site must keep a strong
 *   reference of the observer.
 * - It is possible that the callback may be triggered even there is no real data change. For
 *   example, when data restore/clear happens, it might be too complex to check if data is really
 *   changed, thus all the registered observers are notified directly.
 */
@AnyThread
interface KeyedObservable<K> {
    /**
     * Adds an observer for any key.
     *
     * The observer will be notified whenever a change happens. The [KeyedObserver.onKeyChanged]
     * callback will be invoked with specific key that is modified. However, `null` key is passed in
     * the cases that a bunch of keys are changed simultaneously (e.g. clear data, restore happens).
     *
     * @param observer observer to be notified
     * @param executor executor to run the callback
     */
    fun addObserver(observer: KeyedObserver<K?>, executor: Executor)

    /**
     * Adds an observer on given key.
     *
     * The observer will be notified only when the given key is changed.
     *
     * @param key key to observe
     * @param observer observer to be notified
     * @param executor executor to run the callback
     */
    fun addObserver(key: K, observer: KeyedObserver<K>, executor: Executor)

    /** Removes observer. */
    fun removeObserver(observer: KeyedObserver<K?>)

    /** Removes observer on given key. */
    fun removeObserver(key: K, observer: KeyedObserver<K>)

    /**
     * Notifies all observers that a change occurs.
     *
     * All the any key and keyed observers are notified.
     *
     * @param reason reason of the change
     */
    fun notifyChange(reason: Int)

    /**
     * Notifies observers that a change occurs on given key.
     *
     * The any key and specific key observers are notified.
     *
     * @param key key of the change
     * @param reason reason of the change
     */
    fun notifyChange(key: K, reason: Int)
}

/** A thread safe implementation of [KeyedObservable]. */
class KeyedDataObservable<K> : KeyedObservable<K> {
    // Instead of @GuardedBy("this"), guarded by itself because KeyedDataObservable object could be
    // synchronized outside by the holder
    @GuardedBy("itself") private val observers = WeakHashMap<KeyedObserver<K?>, Executor>()

    @GuardedBy("itself")
    private val keyedObservers = MutableScatterMap<K, WeakHashMap<KeyedObserver<K>, Executor>>()

    override fun addObserver(observer: KeyedObserver<K?>, executor: Executor) {
        val oldExecutor = synchronized(observers) { observers.put(observer, executor) }
        if (oldExecutor != null && oldExecutor != executor) {
            throw IllegalStateException("Add $observer twice, old=$oldExecutor, new=$executor")
        }
    }

    override fun addObserver(key: K, observer: KeyedObserver<K>, executor: Executor) {
        val oldExecutor =
            synchronized(keyedObservers) {
                keyedObservers.getOrPut(key) { WeakHashMap() }.put(observer, executor)
            }
        if (oldExecutor != null && oldExecutor != executor) {
            throw IllegalStateException("Add $observer twice, old=$oldExecutor, new=$executor")
        }
    }

    override fun removeObserver(observer: KeyedObserver<K?>) {
        synchronized(observers) { observers.remove(observer) }
    }

    override fun removeObserver(key: K, observer: KeyedObserver<K>) {
        synchronized(keyedObservers) {
            val observers = keyedObservers[key]
            if (observers?.remove(observer) != null && observers.isEmpty()) {
                keyedObservers.remove(key)
            }
        }
    }

    override fun notifyChange(reason: Int) {
        // make a copy to avoid potential ConcurrentModificationException
        val observers = synchronized(observers) { observers.entries.toTypedArray() }
        val keyedObservers = synchronized(keyedObservers) { keyedObservers.copy() }
        for (entry in observers) {
            val observer = entry.key // avoid reference "entry"
            entry.value.execute { observer.onKeyChanged(null, reason) }
        }
        for (pair in keyedObservers) {
            val key = pair.first
            for (entry in pair.second) {
                val observer = entry.key // avoid reference "entry"
                entry.value.execute { observer.onKeyChanged(key, reason) }
            }
        }
    }

    private fun MutableScatterMap<K, WeakHashMap<KeyedObserver<K>, Executor>>.copy():
        List<Pair<K, Array<Map.Entry<KeyedObserver<K>, Executor>>>> {
        val result = ArrayList<Pair<K, Array<Map.Entry<KeyedObserver<K>, Executor>>>>(size)
        forEach { key, value -> result.add(Pair(key, value.entries.toTypedArray())) }
        return result
    }

    override fun notifyChange(key: K, reason: Int) {
        // make a copy to avoid potential ConcurrentModificationException
        val observers = synchronized(observers) { observers.entries.toTypedArray() }
        val keyedObservers =
            synchronized(keyedObservers) { keyedObservers[key]?.entries?.toTypedArray() }
                ?: arrayOf()
        for (entry in observers) {
            val observer = entry.key // avoid reference "entry"
            entry.value.execute { observer.onKeyChanged(key, reason) }
        }
        for (entry in keyedObservers) {
            val observer = entry.key // avoid reference "entry"
            entry.value.execute { observer.onKeyChanged(key, reason) }
        }
    }
}
