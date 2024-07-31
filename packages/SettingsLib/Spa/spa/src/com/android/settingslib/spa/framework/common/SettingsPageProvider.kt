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
import androidx.compose.runtime.remember
import androidx.navigation.NamedNavArgument
import com.android.settingslib.spa.framework.util.genPageId
import com.android.settingslib.spa.framework.util.normalizeArgList
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

private const val NULL_PAGE_NAME = "NULL"

/**
 * An SettingsPageProvider which is used to create Settings page instances.
 */
interface SettingsPageProvider {

    /** The page provider name, needs to be *unique* and *stable*. */
    val name: String

    /** The category id which is the PageId at SettingsEnums.*/
    val metricsCategory: Int
        get() = 0

    enum class NavType {
        Page,
        Dialog,
    }

    val navType: NavType
        get() = NavType.Page

    /** The display name of this page provider, for better readability. */
    val displayName: String
        get() = name

    /** The page parameters, default is no parameters. */
    val parameter: List<NamedNavArgument>
        get() = emptyList()

    /**
     * The API to indicate whether the page is enabled or not.
     * During SPA page migration, one can use it to enable certain pages in one release.
     * When the page is disabled, all its related functionalities, such as browsing and search,
     * are disabled as well.
     */
    fun isEnabled(arguments: Bundle?): Boolean = true

    fun getTitle(arguments: Bundle?): String = displayName

    fun buildEntry(arguments: Bundle?): List<SettingsEntry> = emptyList()

    /** The [Composable] used to render this page. */
    @Composable
    fun Page(arguments: Bundle?) {
        val title = remember { getTitle(arguments) }
        val entries = remember { buildEntry(arguments) }
        RegularScaffold(title) {
            for (entry in entries) {
                entry.UiLayout()
            }
        }
    }
}

fun SettingsPageProvider.createSettingsPage(arguments: Bundle? = null): SettingsPage {
    return SettingsPage(
        id = genPageId(name, parameter, arguments),
        sppName = name,
        metricsCategory = metricsCategory,
        displayName = displayName + parameter.normalizeArgList(arguments, eraseRuntimeValues = true)
            .joinToString("") { arg -> "/$arg" },
        parameter = parameter,
        arguments = arguments,
    )
}

internal object NullPageProvider : SettingsPageProvider {
    override val name = NULL_PAGE_NAME
}

fun getPageProvider(sppName: String): SettingsPageProvider? {
    if (!SpaEnvironmentFactory.isReady()) return null
    val pageProviderRepository by SpaEnvironmentFactory.instance.pageProviderRepository
    return pageProviderRepository.getProviderOrNull(sppName)
}
