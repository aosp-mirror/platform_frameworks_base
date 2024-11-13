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
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.metadata.EXTRA_BINDING_SCREEN_KEY
import com.android.settingslib.metadata.PersistentPreference
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.PreferenceScreenMetadata
import com.android.settingslib.metadata.PreferenceTitleProvider

/** Binding of preference group associated with [PreferenceCategory]. */
interface PreferenceScreenBinding : PreferenceBinding {

    override fun bind(preference: Preference, metadata: PreferenceMetadata) {
        super.bind(preference, metadata)
        val context = preference.context
        val screenMetadata = metadata as PreferenceScreenMetadata
        // Pass the preference key to fragment, so that the fragment could find associated
        // preference screen registered in PreferenceScreenRegistry
        preference.extras.putString(EXTRA_BINDING_SCREEN_KEY, preference.key)
        if (preference is PreferenceScreen) {
            val screenTitle = screenMetadata.screenTitle
            preference.title =
                if (screenTitle != 0) {
                    context.getString(screenTitle)
                } else {
                    screenMetadata.getScreenTitle(context)
                        ?: (this as? PreferenceTitleProvider)?.getTitle(context)
                }
        }
    }

    companion object {
        @JvmStatic val INSTANCE = object : PreferenceScreenBinding {}
    }
}

/** Binding of preference group associated with [PreferenceCategory]. */
interface PreferenceGroupBinding : PreferenceBinding {

    override fun createWidget(context: Context) = PreferenceCategory(context)

    companion object {
        @JvmStatic val INSTANCE = object : PreferenceGroupBinding {}
    }
}

/** A boolean value type preference associated with [SwitchPreferenceCompat]. */
interface SwitchPreferenceBinding : PreferenceBinding {

    override fun createWidget(context: Context): Preference = SwitchPreferenceCompat(context)

    override fun bind(preference: Preference, metadata: PreferenceMetadata) {
        super.bind(preference, metadata)
        (metadata as? PersistentPreference<*>)
            ?.storage(preference.context)
            ?.getValue(metadata.key, Boolean::class.javaObjectType)
            ?.let { (preference as SwitchPreferenceCompat).isChecked = it }
    }

    companion object {
        @JvmStatic val INSTANCE = object : SwitchPreferenceBinding {}
    }
}

/** Default [PreferenceBinding] for [Preference]. */
object DefaultPreferenceBinding : PreferenceBinding
