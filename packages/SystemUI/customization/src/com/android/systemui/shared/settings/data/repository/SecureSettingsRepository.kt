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

import android.provider.Settings
import kotlinx.coroutines.flow.Flow

/** Defines interface for classes that can provide access to data from [Settings.Secure]. */
interface SecureSettingsRepository {

    /** Returns a [Flow] tracking the value of a setting as an [Int]. */
    fun intSetting(name: String, defaultValue: Int = 0): Flow<Int>

    /** Returns a [Flow] tracking the value of a setting as a [Boolean]. */
    fun boolSetting(name: String, defaultValue: Boolean = false): Flow<Boolean>

    /** Updates the value of the setting with the given name. */
    suspend fun setInt(name: String, value: Int)

    suspend fun getInt(name: String, defaultValue: Int = 0): Int

    suspend fun getString(name: String): String?
}
