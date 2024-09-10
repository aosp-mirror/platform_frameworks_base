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
import android.os.Bundle
import androidx.annotation.XmlRes
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.android.settingslib.metadata.EXTRA_BINDING_SCREEN_KEY
import com.android.settingslib.metadata.PreferenceScreenBindingKeyProvider
import com.android.settingslib.metadata.PreferenceScreenRegistry
import com.android.settingslib.preference.PreferenceScreenBindingHelper.Companion.bindRecursively

/** Fragment to display a preference screen. */
open class PreferenceFragment :
    PreferenceFragmentCompat(), PreferenceScreenProvider, PreferenceScreenBindingKeyProvider {

    private var preferenceScreenBindingHelper: PreferenceScreenBindingHelper? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = createPreferenceScreen()
    }

    fun createPreferenceScreen(): PreferenceScreen? =
        createPreferenceScreen(PreferenceScreenFactory(this))

    override fun createPreferenceScreen(factory: PreferenceScreenFactory): PreferenceScreen? {
        val context = factory.context
        fun createPreferenceScreenFromResource() =
            factory.inflate(getPreferenceScreenResId(context))

        if (!usePreferenceScreenMetadata()) return createPreferenceScreenFromResource()

        val screenKey = getPreferenceScreenBindingKey(context)
        val screenCreator =
            (PreferenceScreenRegistry[screenKey] as? PreferenceScreenCreator)
                ?: return createPreferenceScreenFromResource()

        val preferenceBindingFactory = screenCreator.preferenceBindingFactory
        val preferenceHierarchy = screenCreator.getPreferenceHierarchy(context)
        val preferenceScreen =
            if (screenCreator.hasCompleteHierarchy()) {
                factory.getOrCreatePreferenceScreen().apply {
                    inflatePreferenceHierarchy(preferenceBindingFactory, preferenceHierarchy)
                }
            } else {
                createPreferenceScreenFromResource()?.also {
                    bindRecursively(it, preferenceBindingFactory, preferenceHierarchy)
                } ?: return null
            }
        preferenceScreenBindingHelper =
            PreferenceScreenBindingHelper(
                context,
                preferenceBindingFactory,
                preferenceScreen,
                preferenceHierarchy,
            )
        return preferenceScreen
    }

    /**
     * Returns if preference screen metadata can be used to set up preference screen.
     *
     * This is for flagging purpose. If false (e.g. flag is disabled), xml resource is used to build
     * preference screen.
     */
    protected open fun usePreferenceScreenMetadata(): Boolean = false

    /** Returns the xml resource to create preference screen. */
    @XmlRes protected open fun getPreferenceScreenResId(context: Context): Int = 0

    override fun getPreferenceScreenBindingKey(context: Context): String? =
        arguments?.getString(EXTRA_BINDING_SCREEN_KEY)

    override fun onDestroy() {
        preferenceScreenBindingHelper?.close()
        super.onDestroy()
    }

    companion object {
        /** Returns [PreferenceFragment] instance to display the preference screen of given key. */
        fun of(screenKey: String): PreferenceFragment? {
            val screenMetadata = PreferenceScreenRegistry[screenKey] ?: return null
            if (
                screenMetadata is PreferenceScreenCreator && screenMetadata.hasCompleteHierarchy()
            ) {
                return PreferenceFragment().apply {
                    arguments = Bundle().apply { putString(EXTRA_BINDING_SCREEN_KEY, screenKey) }
                }
            }
            return null
        }
    }
}
