/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.framework.common

import android.util.Log

private const val TAG = "SppRepository"

class SettingsPageProviderRepository(
    allPageProviders: List<SettingsPageProvider>,
    private val rootPages: List<SettingsPage> = emptyList(),
) {
    // Map of page name to its provider.
    private val pageProviderMap: Map<String, SettingsPageProvider>

    init {
        pageProviderMap = allPageProviders.associateBy { it.name }
        Log.d(TAG, "Initialize Completed: ${pageProviderMap.size} spp")
    }

    fun getDefaultStartPage(): String {
        return if (rootPages.isEmpty()) "" else rootPages[0].buildRoute()
    }

    fun getAllRootPages(): Collection<SettingsPage> {
        return rootPages
    }

    fun getAllProviders(): Collection<SettingsPageProvider> {
        return pageProviderMap.values
    }

    fun getProviderOrNull(name: String): SettingsPageProvider? {
        return pageProviderMap[name]
    }
}
