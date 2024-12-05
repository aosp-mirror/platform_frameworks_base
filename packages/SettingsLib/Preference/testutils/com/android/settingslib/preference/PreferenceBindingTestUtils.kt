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

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.preference.Preference
import com.android.settingslib.metadata.PersistentPreference
import com.android.settingslib.metadata.PreferenceMetadata

/** Creates [Preference] widget and binds with metadata. */
@VisibleForTesting
fun <P : Preference> PreferenceMetadata.createAndBindWidget(context: Context): P {
    val binding = PreferenceBindingFactory.defaultFactory.getPreferenceBinding(this)!!
    return (binding.createWidget(context) as P).also {
        if (this is PersistentPreference<*>) {
            storage(context)?.let { keyValueStore ->
                it.preferenceDataStore = PreferenceDataStoreAdapter(keyValueStore)
            }
        }
        binding.bind(it, this)
    }
}
