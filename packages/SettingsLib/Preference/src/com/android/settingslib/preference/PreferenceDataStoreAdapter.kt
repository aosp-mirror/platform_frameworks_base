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

package com.android.settingslib.preference

import androidx.preference.PreferenceDataStore
import com.android.settingslib.datastore.KeyValueStore

/** Adapter to translate [KeyValueStore] into [PreferenceDataStore]. */
class PreferenceDataStoreAdapter(private val keyValueStore: KeyValueStore) : PreferenceDataStore() {

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        keyValueStore.getValue(key, Boolean::class.javaObjectType) ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        keyValueStore.getValue(key, Float::class.javaObjectType) ?: defValue

    override fun getInt(key: String, defValue: Int): Int =
        keyValueStore.getValue(key, Int::class.javaObjectType) ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        keyValueStore.getValue(key, Long::class.javaObjectType) ?: defValue

    override fun getString(key: String, defValue: String?): String? =
        keyValueStore.getValue(key, String::class.javaObjectType) ?: defValue

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (keyValueStore.getValue(key, Set::class.javaObjectType) as Set<String>?) ?: defValues

    override fun putBoolean(key: String, value: Boolean) =
        keyValueStore.setValue(key, Boolean::class.javaObjectType, value)

    override fun putFloat(key: String, value: Float) =
        keyValueStore.setValue(key, Float::class.javaObjectType, value)

    override fun putInt(key: String, value: Int) =
        keyValueStore.setValue(key, Int::class.javaObjectType, value)

    override fun putLong(key: String, value: Long) =
        keyValueStore.setValue(key, Long::class.javaObjectType, value)

    override fun putString(key: String, value: String?) =
        keyValueStore.setValue(key, String::class.javaObjectType, value)

    override fun putStringSet(key: String, values: Set<String>?) =
        keyValueStore.setValue(key, Set::class.javaObjectType, values)
}
