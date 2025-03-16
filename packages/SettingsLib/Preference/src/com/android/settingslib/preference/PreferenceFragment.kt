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
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.XmlRes
import androidx.preference.PreferenceScreen
import com.android.settingslib.metadata.EXTRA_BINDING_SCREEN_KEY
import com.android.settingslib.metadata.PreferenceScreenBindingKeyProvider
import com.android.settingslib.metadata.PreferenceScreenRegistry
import com.android.settingslib.preference.PreferenceScreenBindingHelper.Companion.bindRecursively
import com.android.settingslib.widget.SettingsBasePreferenceFragment

/** Fragment to display a preference screen. */
open class PreferenceFragment :
    SettingsBasePreferenceFragment(), PreferenceScreenProvider, PreferenceScreenBindingKeyProvider {

    protected var preferenceScreenBindingHelper: PreferenceScreenBindingHelper? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = createPreferenceScreen()
    }

    fun createPreferenceScreen(): PreferenceScreen? =
        createPreferenceScreen(PreferenceScreenFactory(this))

    override fun createPreferenceScreen(factory: PreferenceScreenFactory): PreferenceScreen? {
        preferenceScreenBindingHelper?.onDestroy()
        preferenceScreenBindingHelper = null

        val context = factory.context
        fun createPreferenceScreenFromResource() =
            factory.inflate(getPreferenceScreenResId(context))?.also {
                Log.i(TAG, "Load screen " + it.key + " from resource")
            }

        val screenCreator =
            getPreferenceScreenCreator(context) ?: return createPreferenceScreenFromResource()
        val preferenceBindingFactory = screenCreator.preferenceBindingFactory
        val preferenceHierarchy = screenCreator.getPreferenceHierarchy(context)
        val preferenceScreen =
            if (screenCreator.hasCompleteHierarchy()) {
                Log.i(TAG, "Load screen " + screenCreator.key + " from hierarchy")
                factory.getOrCreatePreferenceScreen().apply {
                    inflatePreferenceHierarchy(preferenceBindingFactory, preferenceHierarchy)
                }
            } else {
                Log.i(TAG, "Screen " + screenCreator.key + " is hybrid")
                createPreferenceScreenFromResource()?.also {
                    bindRecursively(it, preferenceBindingFactory, preferenceHierarchy)
                } ?: return null
            }

        preferenceScreenBindingHelper =
            PreferenceScreenBindingHelper(
                context,
                this,
                preferenceBindingFactory,
                preferenceScreen,
                preferenceHierarchy,
            )
        return preferenceScreen
    }

    /** Returns the xml resource to create preference screen. */
    @XmlRes protected open fun getPreferenceScreenResId(context: Context): Int = 0

    protected fun getPreferenceScreenCreator(context: Context): PreferenceScreenCreator? =
        (PreferenceScreenRegistry[getPreferenceScreenBindingKey(context)]
                as? PreferenceScreenCreator)
            ?.run { if (isFlagEnabled(context)) this else null }

    override fun getPreferenceScreenBindingKey(context: Context): String? =
        arguments?.getString(EXTRA_BINDING_SCREEN_KEY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceScreenBindingHelper?.onCreate()
    }

    override fun onStart() {
        super.onStart()
        preferenceScreenBindingHelper?.onStart()
    }

    override fun onResume() {
        super.onResume()
        preferenceScreenBindingHelper?.onResume()
    }

    override fun onPause() {
        preferenceScreenBindingHelper?.onPause()
        super.onPause()
    }

    override fun onStop() {
        preferenceScreenBindingHelper?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        preferenceScreenBindingHelper?.onDestroy()
        preferenceScreenBindingHelper = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        preferenceScreenBindingHelper?.onActivityResult(requestCode, resultCode, data)
    }

    protected fun getPreferenceKeysInHierarchy(): Set<String> =
        preferenceScreenBindingHelper?.let {
            mutableSetOf<String>().apply { it.forEachRecursively { add(it.metadata.key) } }
        } ?: setOf()

    companion object {
        private const val TAG = "PreferenceFragment"
    }
}
