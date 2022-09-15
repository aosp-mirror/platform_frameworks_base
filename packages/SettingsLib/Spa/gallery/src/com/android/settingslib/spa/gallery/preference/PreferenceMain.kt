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

package com.android.settingslib.spa.gallery.preference

import android.os.Bundle
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

private const val TITLE = "Category: Preference"

object PreferenceMainPageProvider : SettingsPageProvider {
    override val name = "PreferenceMain"

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        return listOf(
            PreferencePageProvider.buildInjectEntry()
                .setLink(fromPage = SettingsPage.create(name)).build(),
            SwitchPreferencePageProvider.buildInjectEntry()
                .setLink(fromPage = SettingsPage.create(name)).build(),
            MainSwitchPreferencePageProvider.buildInjectEntry()
                .setLink(fromPage = SettingsPage.create(name)).build(),
            TwoTargetSwitchPreferencePageProvider.buildInjectEntry()
                .setLink(fromPage = SettingsPage.create(name)).build(),
        )
    }

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner = SettingsPage.create(name))
            .setIsAllowSearch(true)
            .setUiLayoutFn {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val onClick = navigator(name)
                })
            }
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(title = TITLE) {
            for (entry in buildEntry(arguments)) {
                entry.UiLayout()
            }
        }
    }
}
