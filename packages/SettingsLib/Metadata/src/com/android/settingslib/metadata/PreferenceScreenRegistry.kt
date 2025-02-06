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

package com.android.settingslib.metadata

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.android.settingslib.datastore.KeyValueStore

/** Registry of all available preference screens in the app. */
object PreferenceScreenRegistry : ReadWritePermitProvider {
    private const val TAG = "ScreenRegistry"

    /** Provider of key-value store. */
    private lateinit var keyValueStoreProvider: KeyValueStoreProvider

    /**
     * Factories of all available [PreferenceScreenMetadata]s.
     *
     * The map key is preference screen key.
     */
    var preferenceScreenMetadataFactories = FixedArrayMap<String, PreferenceScreenMetadataFactory>()

    /** Metrics logger for preference actions triggered by user interaction. */
    var preferenceUiActionMetricsLogger: PreferenceUiActionMetricsLogger? = null

    private var readWritePermitProvider: ReadWritePermitProvider =
        object : ReadWritePermitProvider {}

    /** Sets the [KeyValueStoreProvider]. */
    fun setKeyValueStoreProvider(keyValueStoreProvider: KeyValueStoreProvider) {
        this.keyValueStoreProvider = keyValueStoreProvider
    }

    /**
     * Returns the key-value store for given preference.
     *
     * Must call [setKeyValueStoreProvider] before invoking this method, otherwise
     * [NullPointerException] is raised.
     */
    fun getKeyValueStore(context: Context, preference: PreferenceMetadata): KeyValueStore? =
        keyValueStoreProvider.getKeyValueStore(context, preference)

    /** Creates [PreferenceScreenMetadata] of particular screen. */
    fun create(context: Context, screenCoordinate: PreferenceScreenCoordinate) =
        create(context, screenCoordinate.screenKey, screenCoordinate.args)

    /** Creates [PreferenceScreenMetadata] of particular screen key with given arguments. */
    fun create(context: Context, screenKey: String?, args: Bundle?): PreferenceScreenMetadata? {
        if (screenKey == null) return null
        val factory = preferenceScreenMetadataFactories[screenKey] ?: return null
        val appContext = context.applicationContext
        if (factory is PreferenceScreenMetadataParameterizedFactory) {
            if (args != null) return factory.create(appContext, args)
            // In case the parameterized screen was a normal scree, it is expected to accept
            // Bundle.EMPTY arguments and take care of backward compatibility.
            if (factory.acceptEmptyArguments()) return factory.create(appContext)
            Log.e(TAG, "screen $screenKey is parameterized but args is not provided")
            return null
        } else {
            if (args == null) return factory.create(appContext)
            Log.e(TAG, "screen $screenKey is not parameterized but args is provided")
            return null
        }
    }

    /**
     * Sets the provider to check read write permit. Read and write requests are denied by default.
     */
    fun setReadWritePermitProvider(readWritePermitProvider: ReadWritePermitProvider) {
        this.readWritePermitProvider = readWritePermitProvider
    }

    override fun getReadPermit(
        context: Context,
        callingPid: Int,
        callingUid: Int,
        preference: PreferenceMetadata,
    ) = readWritePermitProvider.getReadPermit(context, callingPid, callingUid, preference)

    override fun getWritePermit(
        context: Context,
        value: Any?,
        callingPid: Int,
        callingUid: Int,
        preference: PreferenceMetadata,
    ) = readWritePermitProvider.getWritePermit(context, value, callingPid, callingUid, preference)
}

/** Provider of [KeyValueStore]. */
fun interface KeyValueStoreProvider {

    /**
     * Returns the key-value store for given preference.
     *
     * Here are some use cases:
     * - provide the default storage for all preferences
     * - determine the storage per preference keys or the interfaces implemented by the preference
     */
    fun getKeyValueStore(context: Context, preference: PreferenceMetadata): KeyValueStore?
}

/** Provider of read and write permit. */
interface ReadWritePermitProvider {

    val defaultReadWritePermit: @ReadWritePermit Int
        get() = ReadWritePermit.DISALLOW

    fun getReadPermit(
        context: Context,
        callingPid: Int,
        callingUid: Int,
        preference: PreferenceMetadata,
    ): @ReadWritePermit Int = defaultReadWritePermit

    fun getWritePermit(
        context: Context,
        value: Any?,
        callingPid: Int,
        callingUid: Int,
        preference: PreferenceMetadata,
    ): @ReadWritePermit Int = defaultReadWritePermit
}
