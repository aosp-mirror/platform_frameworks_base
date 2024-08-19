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

import android.content.SharedPreferences

/** [SharedPreferences] based [KeyedDataObservable]. */
class SharedPreferencesObservable(private val sharedPreferences: SharedPreferences) :
    KeyedDataObservable<String>(), AutoCloseable {

    private val listener = createSharedPreferenceListener()

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun close() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }
}

/** Creates [SharedPreferences.OnSharedPreferenceChangeListener] for [KeyedObservable]. */
internal fun KeyedObservable<String>.createSharedPreferenceListener() =
    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) {
            notifyChange(key, DataChangeReason.UPDATE)
        } else {
            // On Android >= R, SharedPreferences.Editor.clear() will trigger this case
            notifyChange(DataChangeReason.DELETE)
        }
    }
