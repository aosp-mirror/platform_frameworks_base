/*
 * Copyright (C) 2025 The Android Open Source Project
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

/** [PreferenceDataStore] delegate. */
open class PreferenceDataStoreDelegate(internal val delegate: PreferenceDataStore) :
    PreferenceDataStore() {

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        delegate.getBoolean(key, defValue)

    override fun getFloat(key: String, defValue: Float): Float = delegate.getFloat(key, defValue)

    override fun getInt(key: String, defValue: Int): Int = delegate.getInt(key, defValue)

    override fun getLong(key: String, defValue: Long): Long = delegate.getLong(key, defValue)

    override fun getString(key: String, defValue: String?): String? =
        delegate.getString(key, defValue)

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        delegate.getStringSet(key, defValues)

    override fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(key, value)

    override fun putFloat(key: String, value: Float) = delegate.putFloat(key, value)

    override fun putInt(key: String, value: Int) = delegate.putInt(key, value)

    override fun putLong(key: String, value: Long) = delegate.putLong(key, value)

    override fun putString(key: String, value: String?) = delegate.putString(key, value)

    override fun putStringSet(key: String, values: Set<String>?) =
        delegate.putStringSet(key, values)
}
