/*
 * Copyright (C) 2021 The Android Open Source Project
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

import androidx.lifecycle.Observer
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.Assert
import com.android.systemui.util.ListenerSet
import com.android.systemui.util.isNotEmpty
import com.android.systemui.util.traceSection
import java.util.Collections.unmodifiableList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/** Writeable implementation of [NotifLiveDataStore] */
@SysUISingleton
class NotifLiveDataStoreImpl @Inject constructor(
    @Main private val mainExecutor: Executor
) : NotifLiveDataStore {
    private val hasActiveNotifsPrivate = NotifLiveDataImpl(
        name = "hasActiveNotifs",
        initialValue = false,
        mainExecutor
    )
    private val activeNotifCountPrivate = NotifLiveDataImpl(
        name = "activeNotifCount",
        initialValue = 0,
        mainExecutor
    )
    private val activeNotifListPrivate = NotifLiveDataImpl(
        name = "activeNotifList",
        initialValue = listOf<NotificationEntry>(),
        mainExecutor
    )

    override val hasActiveNotifs: NotifLiveData<Boolean> = hasActiveNotifsPrivate
    override val activeNotifCount: NotifLiveData<Int> = activeNotifCountPrivate
    override val activeNotifList: NotifLiveData<List<NotificationEntry>> = activeNotifListPrivate

    /** Set the latest flattened list of notification entries. */
    fun setActiveNotifList(flatEntryList: List<NotificationEntry>) {
        traceSection("NotifLiveDataStore.setActiveNotifList") {
            Assert.isMainThread()
            val unmodifiableCopy = unmodifiableList(flatEntryList.toList())
            // This ensures we set all values before dispatching to any observers
            listOf(
                activeNotifListPrivate.setValueAndProvideDispatcher(unmodifiableCopy),
                activeNotifCountPrivate.setValueAndProvideDispatcher(unmodifiableCopy.size),
                hasActiveNotifsPrivate.setValueAndProvideDispatcher(unmodifiableCopy.isNotEmpty())
            ).forEach { dispatcher -> dispatcher.invoke() }
        }
    }
}

/** Read-write implementation of [NotifLiveData] */
class NotifLiveDataImpl<T>(
    private val name: String,
    initialValue: T,
    @Main private val mainExecutor: Executor
) : NotifLiveData<T> {
    private val syncObservers = ListenerSet<Observer<T>>()
    private val asyncObservers = ListenerSet<Observer<T>>()
    private val atomicValue = AtomicReference(initialValue)
    private var lastAsyncValue: T? = null

    private fun dispatchToAsyncObservers() {
        val value = atomicValue.get()
        if (lastAsyncValue != value) {
            lastAsyncValue = value
            traceSection("NotifLiveData($name).dispatchToAsyncObservers") {
                asyncObservers.forEach { it.onChanged(value) }
            }
        }
    }

    /**
     * Access or set the current value.
     *
     * When setting, sync observers will be dispatched synchronously, and a task will be posted to
     * dispatch the value to async observers.
     */
    override var value: T
        get() = atomicValue.get()
        set(value) = setValueAndProvideDispatcher(value).invoke()

    /**
     * Set the value, and return a function that when invoked will dispatch to the observers.
     *
     * This is intended to allow multiple instances with related data to be updated together and
     * have their dispatchers invoked after all data has been updated.
     */
    fun setValueAndProvideDispatcher(value: T): () -> Unit {
        val oldValue = atomicValue.getAndSet(value)
        if (oldValue != value) {
            return {
                if (syncObservers.isNotEmpty()) {
                    traceSection("NotifLiveData($name).dispatchToSyncObservers") {
                        syncObservers.forEach { it.onChanged(value) }
                    }
                }
                if (asyncObservers.isNotEmpty()) {
                    mainExecutor.execute(::dispatchToAsyncObservers)
                }
            }
        }
        return {}
    }

    override fun addSyncObserver(observer: Observer<T>) {
        syncObservers.addIfAbsent(observer)
    }

    override fun addAsyncObserver(observer: Observer<T>) {
        asyncObservers.addIfAbsent(observer)
    }

    override fun removeObserver(observer: Observer<T>) {
        syncObservers.remove(observer)
        asyncObservers.remove(observer)
    }
}