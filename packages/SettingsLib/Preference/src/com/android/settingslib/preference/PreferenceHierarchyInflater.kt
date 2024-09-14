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
import androidx.preference.PreferenceGroup
import com.android.settingslib.datastore.KeyValueStore
import com.android.settingslib.metadata.PersistentPreference
import com.android.settingslib.metadata.PreferenceHierarchy
import com.android.settingslib.metadata.PreferenceMetadata

/** Inflates [PreferenceHierarchy] into given [PreferenceGroup] recursively. */
fun PreferenceGroup.inflatePreferenceHierarchy(
    preferenceBindingFactory: PreferenceBindingFactory,
    hierarchy: PreferenceHierarchy,
    storages: MutableMap<KeyValueStore, PreferenceDataStore> = mutableMapOf(),
) {
    fun PreferenceMetadata.preferenceBinding() = preferenceBindingFactory.getPreferenceBinding(this)

    hierarchy.metadata.let { it.preferenceBinding()?.bind(this, it) }
    hierarchy.forEach {
        val metadata = it.metadata
        val preferenceBinding = metadata.preferenceBinding() ?: return@forEach
        val widget = preferenceBinding.createWidget(context)
        if (it is PreferenceHierarchy) {
            val preferenceGroup = widget as PreferenceGroup
            // MUST add preference before binding, otherwise exception is raised when add child
            addPreference(preferenceGroup)
            preferenceGroup.inflatePreferenceHierarchy(preferenceBindingFactory, it)
        } else {
            preferenceBinding.bind(widget, metadata)
            (metadata as? PersistentPreference<*>)?.storage(context)?.let { storage ->
                widget.preferenceDataStore =
                    storages.getOrPut(storage) { PreferenceDataStoreAdapter(storage) }
            }
            // MUST add preference after binding for persistent preference to get initial value
            // (preference key is set within bind method)
            addPreference(widget)
        }
    }
}
