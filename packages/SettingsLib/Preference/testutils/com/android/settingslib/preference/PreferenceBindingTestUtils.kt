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
import androidx.preference.PreferenceScreen
import com.android.settingslib.metadata.PersistentPreference
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.PreferenceScreenMetadata
import org.mockito.kotlin.mock

/** Creates [Preference] widget and binds with metadata. */
@Suppress("UNCHECKED_CAST")
@VisibleForTesting
fun <P : Preference> PreferenceMetadata.createAndBindWidget(
    context: Context,
    preferenceScreen: PreferenceScreen? = null,
    preferenceScreenMetadata: PreferenceScreenMetadata = mock(),
): P {
    val binding = PreferenceBindingFactory.defaultFactory.getPreferenceBinding(this)!!
    return (binding.createWidget(context) as P).also {
        if (this is PersistentPreference<*>) {
            it.preferenceDataStore =
                storage(context).toPreferenceDataStore(preferenceScreenMetadata, this)
            // Attach preference to preference screen, otherwise `Preference.performClick` does not
            // interact with underlying datastore
            (preferenceScreen ?: PreferenceScreenFactory(context).getOrCreatePreferenceScreen())
                .addPreference(it)
        }
        binding.bind(it, this)
    }
}
