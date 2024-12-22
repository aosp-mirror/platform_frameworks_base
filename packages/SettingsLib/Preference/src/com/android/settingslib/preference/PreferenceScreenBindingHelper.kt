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
import android.os.Handler
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.android.settingslib.datastore.KeyedDataObservable
import com.android.settingslib.datastore.KeyedObservable
import com.android.settingslib.datastore.KeyedObserver
import com.android.settingslib.metadata.PersistentPreference
import com.android.settingslib.metadata.PreferenceHierarchy
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
    private val preferenceBindingFactory: PreferenceBindingFactory,
    private val preferenceScreen: PreferenceScreen,
    preferenceHierarchy: PreferenceHierarchy,
) : KeyedDataObservable<String>(), AutoCloseable {

    private val handler = Handler(Looper.getMainLooper())
    private val executor =
        object : Executor {
            override fun execute(command: Runnable) {
                handler.post(command)
            }
        }

    private val preferences: ImmutableMap<String, PreferenceMetadata>
    private val dependencies: ImmutableMultimap<String, String>
    private val storages = mutableSetOf<KeyedObservable<String>>()

    private val preferenceObserver: KeyedObserver<String?>

    private val storageObserver =
        KeyedObserver<String?> { key, _ ->
            if (key != null) {
                notifyChange(key, CHANGE_REASON_VALUE)
            }
        }

    private val stateObserver =
        object : PreferenceLifecycleProvider.PreferenceStateObserver {
            override fun onPreferenceStateChanged(preference: PreferenceMetadata) {
                notifyChange(preference.key, CHANGE_REASON_STATE)
            }
        }

    init {
        val preferencesBuilder = ImmutableMap.builder<String, PreferenceMetadata>()
        val dependenciesBuilder = ImmutableMultimap.builder<String, String>()
        fun PreferenceMetadata.addDependency(dependency: PreferenceMetadata) {
            dependenciesBuilder.put(key, dependency.key)
        }

        fun PreferenceMetadata.add() {
            preferencesBuilder.put(key, this)
            dependencyOfEnabledState(context)?.addDependency(this)
            if (this is PreferenceLifecycleProvider) onAttach(context, stateObserver)
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

    override fun close() {
        removeObserver(preferenceObserver)
        val context = preferenceScreen.context
        for (preference in preferences.values) {
            if (preference is PreferenceLifecycleProvider) preference.onDetach(context)
        }
        for (storage in storages) storage.removeObserver(storageObserver)
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
        ) {
            preferenceBindingFactory.bind(this, preferences[key])
            val count = preferenceCount
            for (index in 0 until count) {
                val preference = getPreference(index)
                if (preference is PreferenceGroup) {
                    preference.bindRecursively(preferenceBindingFactory, preferences)
                } else {
                    preferenceBindingFactory.bind(preference, preferences[preference.key])
                }
            }
        }

        private fun PreferenceBindingFactory.bind(
            preference: Preference,
            metadata: PreferenceMetadata?,
        ) = metadata?.let { getPreferenceBinding(it)?.bind(preference, it) }
    }
}
