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

import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceGroup
import com.android.settingslib.datastore.KeyValueStore
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.PreferenceScreenMetadata
import com.android.settingslib.metadata.PreferenceScreenRegistry

/** Traversals preference hierarchy recursively and applies an action. */
fun PreferenceGroup.forEachRecursively(action: (Preference) -> Unit) {
    action.invoke(this)
    for (index in 0 until preferenceCount) {
        val preference = getPreference(index)
        if (preference is PreferenceGroup) {
            preference.forEachRecursively(action)
        } else {
            action.invoke(preference)
        }
    }
}

/**
 * Converts [KeyValueStore] to [PreferenceDataStore].
 *
 * [PreferenceScreenRegistry.preferenceUiActionMetricsLogger] is wrapped on top of
 * [PreferenceDataStoreDelegate] to log metrics.
 *
 * Note: Only user interaction changes are logged.
 */
fun KeyValueStore.toPreferenceDataStore(
    screen: PreferenceScreenMetadata,
    preference: PreferenceMetadata,
): PreferenceDataStore {
    val preferenceDataStore: PreferenceDataStore = PreferenceDataStoreAdapter(this)
    val metricsLogger =
        PreferenceScreenRegistry.preferenceUiActionMetricsLogger ?: return preferenceDataStore
    return object : PreferenceDataStoreDelegate(preferenceDataStore) {
        override fun putBoolean(key: String, value: Boolean) {
            super.putBoolean(key, value)
            metricsLogger.logPreferenceValueChange(screen, preference, value)
        }

        override fun putFloat(key: String, value: Float) {
            super.putFloat(key, value)
            metricsLogger.logPreferenceValueChange(screen, preference, value)
        }

        override fun putInt(key: String, value: Int) {
            super.putInt(key, value)
            metricsLogger.logPreferenceValueChange(screen, preference, value)
        }

        override fun putLong(key: String, value: Long) {
            super.putLong(key, value)
            metricsLogger.logPreferenceValueChange(screen, preference, value)
        }

        override fun putString(key: String, value: String?) {
            super.putString(key, value)
            metricsLogger.logPreferenceValueChange(screen, preference, value)
        }

        override fun putStringSet(key: String, values: Set<String>?) {
            super.putStringSet(key, values)
            metricsLogger.logPreferenceValueChange(screen, preference, values)
        }
    }
}
