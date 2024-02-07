/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.shared.settings.data.repository

import android.content.ContentResolver
import android.database.ContentObserver
import android.provider.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Defines interface for classes that can provide access to data from [Settings.Secure].
 * This repository doesn't guarantee to provide value across different users. For that
 * see: [UserAwareSecureSettingsRepository]
 */
interface SecureSettingsRepository {

    /** Returns a [Flow] tracking the value of a setting as an [Int]. */
    fun intSetting(
        name: String,
        defaultValue: Int = 0,
    ): Flow<Int>

    /** Updates the value of the setting with the given name. */
    suspend fun setInt(
        name: String,
        value: Int,
    )

    suspend fun getInt(
        name: String,
        defaultValue: Int = 0,
    ): Int

    suspend fun getString(name: String): String?
}

class SecureSettingsRepositoryImpl(
    private val contentResolver: ContentResolver,
    private val backgroundDispatcher: CoroutineDispatcher,
) : SecureSettingsRepository {

    override fun intSetting(
        name: String,
        defaultValue: Int,
    ): Flow<Int> {
        return callbackFlow {
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(Unit)
                        }
                    }

                contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(name),
                    /* notifyForDescendants= */ false,
                    observer,
                )
                send(Unit)

                awaitClose { contentResolver.unregisterContentObserver(observer) }
            }
            .map { Settings.Secure.getInt(contentResolver, name, defaultValue) }
            // The above work is done on the background thread (which is important for accessing
            // settings through the content resolver).
            .flowOn(backgroundDispatcher)
    }

    override suspend fun setInt(name: String, value: Int) {
        withContext(backgroundDispatcher) {
            Settings.Secure.putInt(
                contentResolver,
                name,
                value,
            )
        }
    }

    override suspend fun getInt(name: String, defaultValue: Int): Int {
        return withContext(backgroundDispatcher) {
            Settings.Secure.getInt(
                contentResolver,
                name,
                defaultValue,
            )
        }
    }

    override suspend fun getString(name: String): String? {
        return withContext(backgroundDispatcher) {
            Settings.Secure.getString(
                contentResolver,
                name,
            )
        }
    }
}
