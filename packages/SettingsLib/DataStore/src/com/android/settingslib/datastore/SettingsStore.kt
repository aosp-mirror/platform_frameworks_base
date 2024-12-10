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
import android.util.Log

/** Base class of the Settings provider data stores. */
abstract class SettingsStore(protected val contentResolver: ContentResolver) :
    AbstractKeyedDataObservable<String>(), KeyValueStore {

    private val contentObserver =
        object : ContentObserver(HandlerExecutor.main) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val key = uri?.lastPathSegment ?: return
                notifyChange(key, DataChangeReason.UPDATE)
            }
        }

    /** The URI to watch for any key change. */
    protected abstract val uri: Uri

    override fun onFirstObserverAdded() {
        Log.i(tag, "registerContentObserver")
        contentResolver.registerContentObserver(uri, true, contentObserver)
    }

    override fun onLastObserverRemoved() {
        Log.i(tag, "unregisterContentObserver")
        contentResolver.unregisterContentObserver(contentObserver)
    }

    /** Tag for logging. */
    abstract val tag: String
}
