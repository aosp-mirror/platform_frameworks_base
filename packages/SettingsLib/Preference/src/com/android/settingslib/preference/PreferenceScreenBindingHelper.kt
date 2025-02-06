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
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.android.settingslib.datastore.DataChangeReason
import com.android.settingslib.datastore.HandlerExecutor
import com.android.settingslib.datastore.KeyValueStore
import com.android.settingslib.datastore.KeyedDataObservable
import com.android.settingslib.datastore.KeyedObservable
import com.android.settingslib.datastore.KeyedObserver
import com.android.settingslib.metadata.EXTRA_BINDING_SCREEN_ARGS
import com.android.settingslib.metadata.PersistentPreference
import com.android.settingslib.metadata.PreferenceChangeReason
import com.android.settingslib.metadata.PreferenceHierarchy
import com.android.settingslib.metadata.PreferenceHierarchyNode
import com.android.settingslib.metadata.PreferenceLifecycleContext
import com.android.settingslib.metadata.PreferenceLifecycleProvider
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.PreferenceScreenMetadata
import com.android.settingslib.metadata.PreferenceScreenRegistry
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableMultimap

/**
 * Helper to bind preferences on given [preferenceScreen].
 *
 * When there is any preference change event detected (e.g. preference value changed, runtime
 * states, dependency is updated), this helper class will re-bind [PreferenceMetadata] to update
 * widget UI.
 */
class PreferenceScreenBindingHelper(
    context: Context,
    private val fragment: PreferenceFragment,
    private val preferenceBindingFactory: PreferenceBindingFactory,
    private val preferenceScreen: PreferenceScreen,
    private val preferenceHierarchy: PreferenceHierarchy,
) : KeyedDataObservable<String>() {

    private val mainExecutor = HandlerExecutor.main

    private val preferenceLifecycleContext =
        object : PreferenceLifecycleContext(context) {
            override val lifecycleScope: LifecycleCoroutineScope
                get() = fragment.lifecycleScope

            override fun <T> findPreference(key: String) =
                preferenceScreen.findPreference(key) as T?

            override fun <T : Any> requirePreference(key: String) = findPreference<T>(key)!!

            override fun getKeyValueStore(key: String) =
                findPreference<Preference>(key)?.preferenceDataStore?.findKeyValueStore()

            override fun notifyPreferenceChange(key: String) =
                notifyChange(key, PreferenceChangeReason.STATE)

            @Suppress("DEPRECATION")
            override fun startActivityForResult(
                intent: Intent,
                requestCode: Int,
                options: Bundle?,
            ) = fragment.startActivityForResult(intent, requestCode, options)
        }

    private val preferences: ImmutableMap<String, PreferenceHierarchyNode>
    private val dependencies: ImmutableMultimap<String, String>
    private val lifecycleAwarePreferences: Array<PreferenceLifecycleProvider>
    private val storages = mutableMapOf<String, KeyedObservable<String>>()

    private val preferenceObserver: KeyedObserver<String?>

    private val storageObserver =
        KeyedObserver<String> { key, reason ->
            if (DataChangeReason.isDataChange(reason)) {
                notifyChange(key, PreferenceChangeReason.VALUE)
            } else {
                notifyChange(key, PreferenceChangeReason.STATE)
            }
        }

    init {
        val preferencesBuilder = ImmutableMap.builder<String, PreferenceHierarchyNode>()
        val dependenciesBuilder = ImmutableMultimap.builder<String, String>()
        val lifecycleAwarePreferences = mutableListOf<PreferenceLifecycleProvider>()

        fun PreferenceHierarchyNode.addNode() {
            metadata.let {
                val key = it.key
                preferencesBuilder.put(key, this)
                for (dependency in it.dependencies(context)) {
                    dependenciesBuilder.put(dependency, key)
                }
                if (it is PreferenceLifecycleProvider) lifecycleAwarePreferences.add(it)
            }
        }

        fun PreferenceHierarchy.addPreferences() {
            addNode()
            forEach {
                if (it is PreferenceHierarchy) {
                    it.addPreferences()
                } else {
                    it.addNode()
                }
            }
        }

        preferenceHierarchy.addPreferences()
        this.preferences = preferencesBuilder.buildOrThrow()
        this.dependencies = dependenciesBuilder.build()
        this.lifecycleAwarePreferences = lifecycleAwarePreferences.toTypedArray()

        preferenceObserver = KeyedObserver { key, reason -> onPreferenceChange(key, reason) }
        addObserver(preferenceObserver, mainExecutor)

        preferenceScreen.forEachRecursively {
            it.preferenceDataStore?.findKeyValueStore()?.let { keyValueStore ->
                val key = it.key
                storages[key] = keyValueStore
                keyValueStore.addObserver(key, storageObserver, mainExecutor)
            }
        }
    }

    private fun PreferenceDataStore.findKeyValueStore(): KeyValueStore? =
        when (this) {
            is PreferenceDataStoreAdapter -> keyValueStore
            is PreferenceDataStoreDelegate -> delegate.findKeyValueStore()
            else -> null
        }

    private fun onPreferenceChange(key: String?, reason: Int) {
        if (key == null) return

        // bind preference to update UI
        preferenceScreen.findPreference<Preference>(key)?.let {
            val node = preferences[key] ?: return@let
            preferenceBindingFactory.bind(it, node)
            if (it == preferenceScreen) fragment.updateActivityTitle()
        }

        // check reason to avoid potential infinite loop
        if (reason != PreferenceChangeReason.DEPENDENT) {
            notifyDependents(key, mutableSetOf())
        }
    }

    /** Notifies dependents recursively. */
    private fun notifyDependents(key: String, notifiedKeys: MutableSet<String>) {
        if (!notifiedKeys.add(key)) return
        for (dependency in dependencies[key]) {
            notifyChange(dependency, PreferenceChangeReason.DEPENDENT)
            notifyDependents(dependency, notifiedKeys)
        }
    }

    fun forEachRecursively(action: (PreferenceHierarchyNode) -> Unit) =
        preferenceHierarchy.forEachRecursively(action)

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
        for ((key, storage) in storages) storage.removeObserver(key, storageObserver)
        for (preference in lifecycleAwarePreferences) {
            preference.onDestroy(preferenceLifecycleContext)
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        lifecycleAwarePreferences.firstOrNull {
            it.onActivityResult(preferenceLifecycleContext, requestCode, resultCode, data)
        }
    }

    companion object {
        /** Updates preference screen that has incomplete hierarchy. */
        @JvmStatic
        fun bind(preferenceScreen: PreferenceScreen) {
            val context = preferenceScreen.context
            val args = preferenceScreen.peekExtras()?.getBundle(EXTRA_BINDING_SCREEN_ARGS)
            PreferenceScreenRegistry.create(context, preferenceScreen.key, args)?.run {
                if (!hasCompleteHierarchy()) {
                    val preferenceBindingFactory =
                        (this as? PreferenceScreenCreator)?.preferenceBindingFactory ?: return
                    bindRecursively(
                        preferenceScreen,
                        preferenceBindingFactory,
                        getPreferenceHierarchy(context),
                    )
                }
            }
        }

        internal fun bindRecursively(
            preferenceScreen: PreferenceScreen,
            preferenceBindingFactory: PreferenceBindingFactory,
            preferenceHierarchy: PreferenceHierarchy,
        ) {
            val preferenceScreenMetadata = preferenceHierarchy.metadata as PreferenceScreenMetadata
            val preferences = mutableMapOf<String, PreferenceHierarchyNode>()
            preferenceHierarchy.forEachRecursively { preferences[it.metadata.key] = it }
            val storages = mutableMapOf<KeyValueStore, PreferenceDataStore>()

            fun Preference.setPreferenceDataStore(metadata: PreferenceMetadata) {
                (metadata as? PersistentPreference<*>)?.storage(context)?.let { storage ->
                    preferenceDataStore =
                        storages.getOrPut(storage) {
                            storage.toPreferenceDataStore(preferenceScreenMetadata, metadata)
                        }
                }
            }

            fun PreferenceGroup.bindRecursively() {
                preferences.remove(key)?.let { preferenceBindingFactory.bind(this, it) }
                val count = preferenceCount
                for (index in 0 until count) {
                    val preference = getPreference(index)
                    if (preference is PreferenceGroup) {
                        preference.bindRecursively()
                    } else {
                        preferences.remove(preference.key)?.let {
                            preference.setPreferenceDataStore(it.metadata)
                            preferenceBindingFactory.bind(preference, it)
                        }
                    }
                }
            }

            preferenceScreen.bindRecursively()
            for (node in preferences.values) {
                val metadata = node.metadata
                val binding = preferenceBindingFactory.getPreferenceBinding(metadata)
                if (binding !is PreferenceBindingPlaceholder) continue
                val preference = binding.createWidget(preferenceScreen.context)
                preference.setPreferenceDataStore(metadata)
                preferenceBindingFactory.bind(preference, node, binding)
                preferenceScreen.addPreference(preference)
            }
        }
    }
}
