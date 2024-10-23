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
import android.os.Handler
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.android.settingslib.datastore.KeyValueStore
import com.android.settingslib.datastore.KeyedDataObservable
import com.android.settingslib.datastore.KeyedObservable
import com.android.settingslib.datastore.KeyedObserver
import com.android.settingslib.metadata.PersistentPreference
import com.android.settingslib.metadata.PreferenceHierarchy
import com.android.settingslib.metadata.PreferenceLifecycleContext
import com.android.settingslib.metadata.PreferenceLifecycleProvider
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.PreferenceScreenRegistry
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableMultimap
import java.util.concurrent.Executor

/**
 * Helper to bind preferences on given [preferenceScreen].
 *
 * When there is any preference change event detected (e.g. preference value changed, runtime
 * states, dependency is updated), this helper class will re-bind [PreferenceMetadata] to update
 * widget UI.
 */
class PreferenceScreenBindingHelper(
    context: Context,
    fragment: PreferenceFragment,
    private val preferenceBindingFactory: PreferenceBindingFactory,
    private val preferenceScreen: PreferenceScreen,
    private val preferenceHierarchy: PreferenceHierarchy,
) : KeyedDataObservable<String>() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor =
        object : Executor {
            override fun execute(command: Runnable) {
                handler.post(command)
            }
        }

    private val preferenceLifecycleContext =
        object : PreferenceLifecycleContext(context) {
            override fun notifyPreferenceChange(preference: PreferenceMetadata) =
                notifyChange(preference.key, CHANGE_REASON_STATE)

            @Suppress("DEPRECATION")
            override fun startActivityForResult(
                intent: Intent,
                requestCode: Int,
                options: Bundle?,
            ) = fragment.startActivityForResult(intent, requestCode, options)
        }

    private val preferences: ImmutableMap<String, PreferenceMetadata>
    private val dependencies: ImmutableMultimap<String, String>
    private val lifecycleAwarePreferences: Array<PreferenceLifecycleProvider>
    private val storages = mutableSetOf<KeyedObservable<String>>()

    private val preferenceObserver: KeyedObserver<String?>

    private val storageObserver =
        KeyedObserver<String?> { key, _ ->
            if (key != null) {
                notifyChange(key, CHANGE_REASON_VALUE)
            }
        }

    init {
        val preferencesBuilder = ImmutableMap.builder<String, PreferenceMetadata>()
        val dependenciesBuilder = ImmutableMultimap.builder<String, String>()
        val lifecycleAwarePreferences = mutableListOf<PreferenceLifecycleProvider>()
        fun PreferenceMetadata.addDependency(dependency: PreferenceMetadata) {
            dependenciesBuilder.put(key, dependency.key)
        }

        fun PreferenceMetadata.add() {
            preferencesBuilder.put(key, this)
            dependencyOfEnabledState(context)?.addDependency(this)
            if (this is PreferenceLifecycleProvider) lifecycleAwarePreferences.add(this)
            if (this is PersistentPreference<*>) storages.add(storage(context))
        }

        fun PreferenceHierarchy.addPreferences() {
            metadata.add()
            forEach {
                if (it is PreferenceHierarchy) {
                    it.addPreferences()
                } else {
                    it.metadata.add()
                }
            }
        }

        preferenceHierarchy.addPreferences()
        this.preferences = preferencesBuilder.buildOrThrow()
        this.dependencies = dependenciesBuilder.build()
        this.lifecycleAwarePreferences = lifecycleAwarePreferences.toTypedArray()

        preferenceObserver = KeyedObserver { key, reason -> onPreferenceChange(key, reason) }
        addObserver(preferenceObserver, executor)
        for (storage in storages) storage.addObserver(storageObserver, executor)
    }

    private fun onPreferenceChange(key: String?, reason: Int) {
        if (key == null) return

        // bind preference to update UI
        preferenceScreen.findPreference<Preference>(key)?.let {
            preferenceBindingFactory.bind(it, preferences[key])
        }

        // check reason to avoid potential infinite loop
        if (reason != CHANGE_REASON_DEPENDENT) {
            notifyDependents(key, mutableSetOf())
        }
    }

    /** Notifies dependents recursively. */
    private fun notifyDependents(key: String, notifiedKeys: MutableSet<String>) {
        if (!notifiedKeys.add(key)) return
        for (dependency in dependencies[key]) {
            notifyChange(dependency, CHANGE_REASON_DEPENDENT)
            notifyDependents(dependency, notifiedKeys)
        }
    }

    fun getPreferences() = preferenceHierarchy.getAllPreferences()

    fun onCreate() {
        for (preference in lifecycleAwarePreferences) {
            preference.onCreate(preferenceLifecycleContext)
        }
    }

    fun onStart() {
        for (preference in lifecycleAwarePreferences) {
            preference.onStart(preferenceLifecycleContext)
        }
    }

    fun onResume() {
        for (preference in lifecycleAwarePreferences) {
            preference.onResume(preferenceLifecycleContext)
        }
    }

    fun onPause() {
        for (preference in lifecycleAwarePreferences) {
            preference.onPause(preferenceLifecycleContext)
        }
    }

    fun onStop() {
        for (preference in lifecycleAwarePreferences) {
            preference.onStop(preferenceLifecycleContext)
        }
    }

    fun onDestroy() {
        removeObserver(preferenceObserver)
        for (storage in storages) storage.removeObserver(storageObserver)
        for (preference in lifecycleAwarePreferences) {
            preference.onDestroy(preferenceLifecycleContext)
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        for (preference in lifecycleAwarePreferences) {
            if (preference.onActivityResult(requestCode, resultCode, data)) break
        }
    }

    companion object {
        /** Preference value is changed. */
        private const val CHANGE_REASON_VALUE = 0
        /** Preference state (title/summary, enable state, etc.) is changed. */
        private const val CHANGE_REASON_STATE = 1
        /** Dependent preference state is changed. */
        private const val CHANGE_REASON_DEPENDENT = 2

        /** Updates preference screen that has incomplete hierarchy. */
        @JvmStatic
        fun bind(preferenceScreen: PreferenceScreen) {
            PreferenceScreenRegistry[preferenceScreen.key]?.run {
                if (!hasCompleteHierarchy()) {
                    val preferenceBindingFactory =
                        (this as? PreferenceScreenCreator)?.preferenceBindingFactory ?: return
                    bindRecursively(
                        preferenceScreen,
                        preferenceBindingFactory,
                        getPreferenceHierarchy(preferenceScreen.context),
                    )
                }
            }
        }

        internal fun bindRecursively(
            preferenceScreen: PreferenceScreen,
            preferenceBindingFactory: PreferenceBindingFactory,
            preferenceHierarchy: PreferenceHierarchy,
        ) =
            preferenceScreen.bindRecursively(
                preferenceBindingFactory,
                preferenceHierarchy.getAllPreferences().associateBy { it.key },
            )

        private fun PreferenceGroup.bindRecursively(
            preferenceBindingFactory: PreferenceBindingFactory,
            preferences: Map<String, PreferenceMetadata>,
            storages: MutableMap<KeyValueStore, PreferenceDataStore> = mutableMapOf(),
        ) {
            preferenceBindingFactory.bind(this, preferences[key])
            val count = preferenceCount
            for (index in 0 until count) {
                val preference = getPreference(index)
                if (preference is PreferenceGroup) {
                    preference.bindRecursively(preferenceBindingFactory, preferences, storages)
                } else {
                    preferences[preference.key]?.let {
                        preferenceBindingFactory.getPreferenceBinding(it)?.bind(preference, it)
                        (it as? PersistentPreference<*>)?.storage(context)?.let { storage ->
                            preference.preferenceDataStore =
                                storages.getOrPut(storage) { PreferenceDataStoreAdapter(storage) }
                        }
                    }
                }
            }
        }

        private fun PreferenceBindingFactory.bind(
            preference: Preference,
            metadata: PreferenceMetadata?,
        ) = metadata?.let { getPreferenceBinding(it)?.bind(preference, it) }
    }
}
