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
import com.android.settingslib.metadata.MainSwitchPreference
import com.android.settingslib.metadata.PreferenceCategory
import com.android.settingslib.metadata.PreferenceHierarchyNode
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.SwitchPreference

/** Factory to map [PreferenceMetadata] to [PreferenceBinding]. */
interface PreferenceBindingFactory {

    /**
     * Binds [Preference] and its associated [PreferenceMetadata] with given [PreferenceBinding]
     * (`getPreferenceBinding(metadata)` is used if [preferenceBinding] is `null`).
     *
     * Subclass could override this callback to handle common binding logic. For instance,
     * restricted preference with policy transparency.
     */
    fun bind(
        preference: Preference,
        node: PreferenceHierarchyNode,
        preferenceBinding: PreferenceBinding? = null,
    ) {
        val binding = (preferenceBinding ?: getPreferenceBinding(node.metadata)) ?: return
        binding.bind(preference, node.metadata)
        node.order?.let { preference.order = it }
    }

    /** Returns the [PreferenceBinding] associated with the [PreferenceMetadata]. */
    fun getPreferenceBinding(metadata: PreferenceMetadata): PreferenceBinding?

    companion object {
        /** Default preference binding factory. */
        @JvmStatic var defaultFactory: PreferenceBindingFactory = DefaultPreferenceBindingFactory()
    }
}

/** Default [PreferenceBindingFactory]. */
open class DefaultPreferenceBindingFactory : PreferenceBindingFactory {

    override fun getPreferenceBinding(metadata: PreferenceMetadata) =
        metadata as? PreferenceBinding
            ?: when (metadata) {
                is SwitchPreference -> SwitchPreferenceBinding.INSTANCE
                is PreferenceCategory -> PreferenceCategoryBinding.INSTANCE
                is PreferenceScreenCreator -> PreferenceScreenBinding.INSTANCE
                is MainSwitchPreference -> MainSwitchPreferenceBinding.INSTANCE
                else -> DefaultPreferenceBinding
            }
}

/** A preference key based binding factory. */
class KeyedPreferenceBindingFactory(private val bindings: Map<String, PreferenceBinding>) :
    PreferenceBindingFactory {

    override fun getPreferenceBinding(metadata: PreferenceMetadata) =
        bindings[metadata.key]
            ?: PreferenceBindingFactory.defaultFactory.getPreferenceBinding(metadata)
}
