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

package com.android.systemui.util.kotlin

import android.content.SharedPreferences
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

object SharedPreferencesExt {
    /**
     * Returns a flow of [Unit] that is invoked each time shared preference is updated.
     *
     * @param key Optional key to limit updates to a particular key.
     */
    fun SharedPreferences.observe(key: String? = null): Flow<Unit> =
        conflatedCallbackFlow {
                val listener =
                    SharedPreferences.OnSharedPreferenceChangeListener { _, key -> trySend(key) }
                registerOnSharedPreferenceChangeListener(listener)
                awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
            }
            .mapNotNull { changedKey -> if ((key ?: changedKey) == changedKey) Unit else null }
}
