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
import com.android.settingslib.datastore.KeyValueStore
import com.google.common.base.Supplier
import com.google.common.base.Suppliers
import com.google.common.collect.ImmutableMap

private typealias PreferenceScreenMap = ImmutableMap<String, PreferenceScreenMetadata>

/** Registry of all available preference screens in the app. */
object PreferenceScreenRegistry : ReadWritePermitProvider {

    /** Provider of key-value store. */
    private lateinit var keyValueStoreProvider: KeyValueStoreProvider

    private var preferenceScreensSupplier: Supplier<PreferenceScreenMap> = Supplier {
        ImmutableMap.of()
    }

    private val preferenceScreens: PreferenceScreenMap
        get() = preferenceScreensSupplier.get()

    private var readWritePermitProvider: ReadWritePermitProvider? = null

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

    /** Sets supplier to provide available preference screens. */
    fun setPreferenceScreensSupplier(supplier: Supplier<List<PreferenceScreenMetadata>>) {
        preferenceScreensSupplier =
            Suppliers.memoize {
                val screensBuilder = ImmutableMap.builder<String, PreferenceScreenMetadata>()
                for (screen in supplier.get()) screensBuilder.put(screen.key, screen)
                screensBuilder.buildOrThrow()
            }
    }

    /** Sets available preference screens. */
    fun setPreferenceScreens(vararg screens: PreferenceScreenMetadata) {
        val screensBuilder = ImmutableMap.builder<String, PreferenceScreenMetadata>()
        for (screen in screens) screensBuilder.put(screen.key, screen)
        preferenceScreensSupplier = Suppliers.ofInstance(screensBuilder.buildOrThrow())
    }

    /** Returns [PreferenceScreenMetadata] of particular key. */
    operator fun get(key: String?): PreferenceScreenMetadata? =
        if (key != null) preferenceScreens[key] else null

    /**
     * Sets the provider to check read write permit. Read and write requests are denied by default.
     */
    fun setReadWritePermitProvider(readWritePermitProvider: ReadWritePermitProvider?) {
        this.readWritePermitProvider = readWritePermitProvider
    }

    override fun getReadPermit(
        context: Context,
        myUid: Int,
        callingUid: Int,
        preference: PreferenceMetadata,
    ) =
        readWritePermitProvider?.getReadPermit(context, myUid, callingUid, preference)
            ?: ReadWritePermit.DISALLOW

    override fun getWritePermit(
        context: Context,
        value: Any?,
        myUid: Int,
        callingUid: Int,
        preference: PreferenceMetadata,
    ) =
        readWritePermitProvider?.getWritePermit(context, value, myUid, callingUid, preference)
            ?: ReadWritePermit.DISALLOW
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

    @ReadWritePermit
    fun getReadPermit(
        context: Context,
        myUid: Int,
        callingUid: Int,
        preference: PreferenceMetadata,
    ): Int

    @ReadWritePermit
    fun getWritePermit(
        context: Context,
        value: Any?,
        myUid: Int,
        callingUid: Int,
        preference: PreferenceMetadata,
    ): Int

    companion object {
        @JvmField
        val ALLOW_ALL_READ_WRITE =
            object : ReadWritePermitProvider {
                override fun getReadPermit(
                    context: Context,
                    myUid: Int,
                    callingUid: Int,
                    preference: PreferenceMetadata,
                ) = ReadWritePermit.ALLOW

                override fun getWritePermit(
                    context: Context,
                    value: Any?,
                    myUid: Int,
                    callingUid: Int,
                    preference: PreferenceMetadata,
                ) = ReadWritePermit.ALLOW
            }
    }
}
