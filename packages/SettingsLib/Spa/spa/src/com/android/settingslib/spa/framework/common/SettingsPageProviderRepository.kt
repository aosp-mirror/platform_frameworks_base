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

class SettingsPageProviderRepository(
    allPagesList: List<SettingsPageProvider>,
    private val rootPages: List<String> = emptyList(),
    // TODO: deprecate rootPages above
    private val rootPageData: List<SettingsPage> = emptyList(),
) {
    // Map of page name to its provider.
    private val pageProviderMap: Map<String, SettingsPageProvider> =
        allPagesList.associateBy { it.name }

    fun getDefaultStartPageName(): String {
        if (rootPageData.isNotEmpty()) {
            return rootPageData[0].name
        } else {
            return rootPages.getOrElse(0) {
                return ""
            }
        }
    }

    fun getAllRootPages(): Collection<SettingsPage> {
        return rootPageData
    }

    fun getAllProviders(): Collection<SettingsPageProvider> {
        return pageProviderMap.values
    }

    fun getProviderOrNull(name: String): SettingsPageProvider? {
        return pageProviderMap[name]
    }
}
