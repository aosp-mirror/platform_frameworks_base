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
import androidx.annotation.IntDef
import java.util.WeakHashMap
import java.util.concurrent.Executor

/** The reason of a change. */
@IntDef(
    ChangeReason.UNKNOWN,
    ChangeReason.UPDATE,
    ChangeReason.DELETE,
    ChangeReason.RESTORE,
    ChangeReason.SYNC_ACROSS_PROFILES,
)
@Retention(AnnotationRetention.SOURCE)
annotation class ChangeReason {
    companion object {
        /** Unknown reason of the change. */
        const val UNKNOWN = 0
        /** Data is updated. */
        const val UPDATE = 1
        /** Data is deleted. */
        const val DELETE = 2
        /** Data is restored from backup/restore framework. */
        const val RESTORE = 3
        /** Data is synced from another profile (e.g. personal profile to work profile). */
        const val SYNC_ACROSS_PROFILES = 4
    }
}

/**
 * Callback to be informed of changes in [Observable] object.
 *
 * The observer is weakly referenced, a strong reference must be kept.
 */
fun interface Observer {
    /**
     * Called by [Observable] in the event of changes.
     *
     * This callback will run in the given [Executor] when observer is added.
     *
     * @param reason the reason of change
     * @see [Observable.addObserver] for the notices.
     */
    fun onChanged(@ChangeReason reason: Int)
}

/** An observable object allows to observe change with [Observer]. */
@AnyThread
interface Observable {
    /**
     * Adds an observer.
     *
     * Notes:
     * - The order in which observers will be notified is unspecified.
     * - The observer is weakly referenced to avoid memory leaking, the call site must keep a strong
     *   reference of the observer.
     * - It is possible that the callback may be triggered even there is no real data change. For
     *   example, when data restore/clear happens, it might be too complex to check if data is
     *   really changed, thus all the registered observers are notified directly.
     *
     * @param observer observer to be notified
     * @param executor executor to run the [Observer.onChanged] callback
     */
    fun addObserver(observer: Observer, executor: Executor)

    /** Removes given observer. */
    fun removeObserver(observer: Observer)

    /**
     * Notifies observers that a change occurs.
     *
     * @param reason reason of the change
     */
    fun notifyChange(@ChangeReason reason: Int)
}

/** A thread safe implementation of [Observable]. */
class DataObservable : Observable {
    // Instead of @GuardedBy("this"), guarded by itself because DataObservable object could be
    // synchronized outside by the holder
    @GuardedBy("itself") private val observers = WeakHashMap<Observer, Executor>()

    override fun addObserver(observer: Observer, executor: Executor) {
        val oldExecutor = synchronized(observers) { observers.put(observer, executor) }
        if (oldExecutor != null && oldExecutor != executor) {
            throw IllegalStateException("Add $observer twice, old=$oldExecutor, new=$executor")
        }
    }

    override fun removeObserver(observer: Observer) {
        synchronized(observers) { observers.remove(observer) }
    }

    override fun notifyChange(@ChangeReason reason: Int) {
        // make a copy to avoid potential ConcurrentModificationException
        val entries = synchronized(observers) { observers.entries.toTypedArray() }
        for (entry in entries) {
            val observer = entry.key // avoid reference "entry"
            entry.value.execute { observer.onChanged(reason) }
        }
    }
}
