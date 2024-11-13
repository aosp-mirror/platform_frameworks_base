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

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Base class of the Settings provider data stores. */
open abstract class SettingsStore(protected val contentResolver: ContentResolver) :
    KeyedDataObservable<String>(), KeyValueStore {

    /**
     * Counter of observers.
     *
     * The value is accurate only when [addObserver] and [removeObserver] are called correctly. When
     * an observer is not removed (and its weak reference is garbage collected), the content
     * observer is not unregistered but this is not a big deal.
     */
    private val counter = AtomicInteger()

    private val contentObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val key = uri?.lastPathSegment ?: return
                notifyChange(key, DataChangeReason.UPDATE)
            }
        }

    override fun addObserver(observer: KeyedObserver<String?>, executor: Executor) =
        if (super.addObserver(observer, executor)) {
            onObserverAdded()
            true
        } else {
            false
        }

    override fun addObserver(key: String, observer: KeyedObserver<String>, executor: Executor) =
        if (super.addObserver(key, observer, executor)) {
            onObserverAdded()
            true
        } else {
            false
        }

    private fun onObserverAdded() {
        if (counter.getAndIncrement() != 0) return
        Log.i(tag, "registerContentObserver")
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(""),
            true,
            contentObserver,
        )
    }

    override fun removeObserver(observer: KeyedObserver<String?>) =
        if (super.removeObserver(observer)) {
            onObserverRemoved()
            true
        } else {
            false
        }

    override fun removeObserver(key: String, observer: KeyedObserver<String>) =
        if (super.removeObserver(key, observer)) {
            onObserverRemoved()
            true
        } else {
            false
        }

    private fun onObserverRemoved() {
        if (counter.decrementAndGet() != 0) return
        Log.i(tag, "unregisterContentObserver")
        contentResolver.unregisterContentObserver(contentObserver)
    }

    /** Tag for logging. */
    abstract val tag: String
}
