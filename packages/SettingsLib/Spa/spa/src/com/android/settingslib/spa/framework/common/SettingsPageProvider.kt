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
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

/**
 * An SettingsPageProvider which is used to create Settings page instances.
 */
interface SettingsPageProvider {

    /** The page provider name, needs to be *unique* and *stable*. */
    val name: String

    /** The display name of this page provider, for better readability. */
    val displayName: String
        get() = name

    /** The page parameters, default is no parameters. */
    val parameter: List<NamedNavArgument>
        get() = emptyList()

    /**
     * The API to indicate whether the page is enabled or not.
     * During SPA page migration, one can use it to enable certain pages in one release.
     * When the page is disabled, all its related functionalities, such as browsing, search,
     * slice provider, are disabled as well.
     */
    fun isEnabled(arguments: Bundle?): Boolean = true

    fun getTitle(arguments: Bundle?): String = displayName

    fun buildEntry(arguments: Bundle?): List<SettingsEntry> = emptyList()

    /** The [Composable] used to render this page. */
    @Composable
    fun Page(arguments: Bundle?) {
        RegularScaffold(title = getTitle(arguments)) {
            for (entry in buildEntry(arguments)) {
                entry.UiLayout()
            }
        }
    }
}
