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
 *
 */

package com.android.systemui.settings

import android.annotation.UserIdInt
import android.content.Context
import android.content.SharedPreferences
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Extension functions for [UserFileManager]. */
object UserFileManagerExt {

    /** Returns a flow of [Unit] that is invoked each time the shared preference is updated. */
    fun UserFileManager.observeSharedPreferences(
        fileName: String,
        @Context.PreferencesMode mode: Int,
        @UserIdInt userId: Int
    ): Flow<Unit> = conflatedCallbackFlow {
        val sharedPrefs = getSharedPreferences(fileName, mode, userId)

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }

        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
