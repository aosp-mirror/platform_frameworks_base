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

/**
 * An object which provides pieces of information about the notification shade.
 *
 * Note that individual fields of this object are updated together before synchronous observers are
 * notified, so synchronous observers of two fields can be assured that they will see consistent
 * results: e.g. if [hasActiveNotifs] is false then [activeNotifList] will be empty, and vice versa.
 *
 * This interface is read-only.
 */
interface NotifLiveDataStore {
    val hasActiveNotifs: NotifLiveData<Boolean>
    val activeNotifCount: NotifLiveData<Int>
    val activeNotifList: NotifLiveData<List<NotificationEntry>>
}

/**
 * An individual value which can be accessed directly, or observed for changes either synchronously
 * or asynchronously.
 *
 * This interface is read-only.
 */
interface NotifLiveData<T> {
    /** Access the current value */
    val value: T
    /** Add an observer which will be invoked synchronously when the value is changed. */
    fun addSyncObserver(observer: Observer<T>)
    /** Add an observer which will be invoked asynchronously after the value has changed */
    fun addAsyncObserver(observer: Observer<T>)
    /** Remove an observer previously added with [addSyncObserver] or [addAsyncObserver]. */
    fun removeObserver(observer: Observer<T>)
}
