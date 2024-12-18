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

package com.android.systemui.shared.settings.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeSystemSettingsRepository : SystemSettingsRepository {

    private val settings = MutableStateFlow<Map<String, String>>(mutableMapOf())

    override fun intSetting(name: String, defaultValue: Int): Flow<Int> {
        return settings.map { it.getOrDefault(name, defaultValue.toString()) }.map { it.toInt() }
    }

    override suspend fun setInt(name: String, value: Int) {
        settings.value = settings.value.toMutableMap().apply { this[name] = value.toString() }
    }

    override suspend fun getInt(name: String, defaultValue: Int): Int {
        return settings.value[name]?.toInt() ?: defaultValue
    }

    override suspend fun getString(name: String): String? {
        return settings.value[name]
    }
}
