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
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import com.android.settingslib.metadata.PreferenceScreenRegistry

/** Factory to create preference screen. */
class PreferenceScreenFactory {
    /** Preference manager to create/inflate preference screen. */
    val preferenceManager: PreferenceManager

    /**
     * Optional existing hierarchy to merge the new hierarchies into.
     *
     * Provide existing hierarchy will preserve the internal state (e.g. scrollbar position) for
     * [PreferenceFragmentCompat].
     */
    private val rootScreen: PreferenceScreen?

    /**
     * Factory constructor from preference fragment.
     *
     * The fragment must be within a valid lifecycle.
     */
    constructor(preferenceFragment: PreferenceFragmentCompat) {
        preferenceManager = preferenceFragment.preferenceManager
        rootScreen = preferenceFragment.preferenceScreen
    }

    /** Factory constructor from [Context]. */
    constructor(context: Context) : this(PreferenceManager(context))

    /** Factory constructor from [PreferenceManager]. */
    constructor(preferenceManager: PreferenceManager) {
        this.preferenceManager = preferenceManager
        rootScreen = null
    }

    /** Context of the factory to create preference screen. */
    val context: Context
        get() = preferenceManager.context

    /** Returns the existing hierarchy or create a new empty preference screen. */
    fun getOrCreatePreferenceScreen(): PreferenceScreen =
        rootScreen ?: preferenceManager.createPreferenceScreen(context)

    /**
     * Inflates [PreferenceScreen] from xml resource.
     *
     * @param xmlRes The resource ID of the XML to inflate
     * @return The root hierarchy (if one was not provided, the new hierarchy's root)
     */
    fun inflate(xmlRes: Int): PreferenceScreen? =
        if (xmlRes != 0) {
            preferenceManager.inflateFromResource(preferenceManager.context, xmlRes, rootScreen)
        } else {
            rootScreen
        }

    /**
     * Creates [PreferenceScreen] of given key.
     *
     * The screen must be registered in [PreferenceScreenFactory] and provide a complete hierarchy.
     */
    fun createBindingScreen(screenKey: String?): PreferenceScreen? {
        val metadata = PreferenceScreenRegistry[screenKey] ?: return null
        if (metadata is PreferenceScreenCreator && metadata.hasCompleteHierarchy()) {
            return metadata.createPreferenceScreen(this)
        }
        return null
    }

    companion object {
        /** Creates [PreferenceScreen] from [PreferenceScreenRegistry]. */
        @JvmStatic
        fun createBindingScreen(preference: Preference): PreferenceScreen? {
            val preferenceScreenCreator =
                (PreferenceScreenRegistry[preference.key] as? PreferenceScreenCreator)
                    ?: return null
            if (!preferenceScreenCreator.hasCompleteHierarchy()) return null
            val factory = PreferenceScreenFactory(preference.context)
            val preferenceScreen = preferenceScreenCreator.createPreferenceScreen(factory)
            factory.preferenceManager.setPreferences(preferenceScreen)
            return preferenceScreen
        }
    }
}
