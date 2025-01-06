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
import androidx.preference.TwoStatePreference
import com.android.settingslib.metadata.EXTRA_BINDING_SCREEN_KEY
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.PreferenceScreenMetadata
import com.android.settingslib.metadata.PreferenceTitleProvider
import com.android.settingslib.widget.MainSwitchPreference

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

/** Binding of preference category associated with [PreferenceCategory]. */
interface PreferenceCategoryBinding : PreferenceBinding {

    override fun createWidget(context: Context) = PreferenceCategory(context)

    companion object {
        @JvmStatic val INSTANCE = object : PreferenceCategoryBinding {}
    }
}

/** A boolean value type preference associated with the abstract [TwoStatePreference]. */
interface TwoStatePreferenceBinding : PreferenceBinding {

    override fun bind(preference: Preference, metadata: PreferenceMetadata) {
        super.bind(preference, metadata)
        (preference as TwoStatePreference).apply {
            // "false" is kind of placeholder, metadata datastore should provide the default value
            isChecked = preferenceDataStore!!.getBoolean(key, false)
        }
    }
}

/** A boolean value type preference associated with [SwitchPreferenceCompat]. */
interface SwitchPreferenceBinding : TwoStatePreferenceBinding {

    override fun createWidget(context: Context): Preference = SwitchPreferenceCompat(context)

    companion object {
        @JvmStatic val INSTANCE = object : SwitchPreferenceBinding {}
    }
}

/** A boolean value type preference associated with [MainSwitchPreference]. */
interface MainSwitchPreferenceBinding : TwoStatePreferenceBinding {

    override fun createWidget(context: Context): Preference = MainSwitchPreference(context)

    companion object {
        @JvmStatic val INSTANCE = object : MainSwitchPreferenceBinding {}
    }
}

/** Default [PreferenceBinding] for [Preference]. */
object DefaultPreferenceBinding : PreferenceBinding
