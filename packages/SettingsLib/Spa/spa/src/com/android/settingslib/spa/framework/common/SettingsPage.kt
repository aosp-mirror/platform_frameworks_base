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

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import com.android.settingslib.spa.framework.util.genPageId
import com.android.settingslib.spa.framework.util.isRuntimeParam
import com.android.settingslib.spa.framework.util.navLink

/**
 * Defines data to identify a Settings page.
 */
data class SettingsPage(
    // The unique id of this page, which is computed by sppName + normalized(arguments)
    val id: String,

    // The name of the page provider, who creates this page. It is used to compute the unique id.
    val sppName: String,

    // The category id of the page provider which is the PageId at SettingsEnums.
    val metricsCategory: Int = 0,

    // The display name of the page, for better readability.
    val displayName: String,

    // The parameters defined in its page provider.
    val parameter: List<NamedNavArgument> = emptyList(),

    // The arguments of this page.
    val arguments: Bundle? = null,
) {
    companion object {
        // TODO: cleanup it once all its usage in Settings are switched to Spp.createSettingsPage
        fun create(
            name: String,
            metricsCategory: Int = 0,
            displayName: String? = null,
            parameter: List<NamedNavArgument> = emptyList(),
            arguments: Bundle? = null
        ): SettingsPage {
            return SettingsPage(
                id = genPageId(name, parameter, arguments),
                sppName = name,
                metricsCategory = metricsCategory,
                displayName = displayName ?: name,
                parameter = parameter,
                arguments = arguments
            )
        }
    }

    // Returns if this Settings Page is created by the given Spp.
    fun isCreateBy(SppName: String): Boolean {
        return sppName == SppName
    }

    fun buildRoute(): String {
        return sppName + parameter.navLink(arguments)
    }

    fun isBrowsable(): Boolean {
        if (sppName == NullPageProvider.name) return false
        for (navArg in parameter) {
            if (navArg.isRuntimeParam()) return false
        }
        return true
    }

    fun isEnabled(): Boolean =
        SpaEnvironment.IS_DEBUG || getPageProvider(sppName)?.isEnabled(arguments) ?: false

    fun getTitle(): String {
        return getPageProvider(sppName)?.getTitle(arguments) ?: ""
    }

    @Composable
    fun UiLayout() {
        getPageProvider(sppName)?.Page(arguments)
    }
}
