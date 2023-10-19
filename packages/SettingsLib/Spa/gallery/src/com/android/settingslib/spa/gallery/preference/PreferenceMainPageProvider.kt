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
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

private const val TITLE = "Category: Preference"

object PreferenceMainPageProvider : SettingsPageProvider {
    override val name = "PreferenceMain"
    private val owner = createSettingsPage()

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        return listOf(
            PreferencePageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            SwitchPreferencePageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            MainSwitchPreferencePageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            ListPreferencePageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            TwoTargetSwitchPreferencePageProvider.buildInjectEntry()
                .setLink(fromPage = owner).build(),
        )
    }

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner = owner)
            .setUiLayoutFn {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val onClick = navigator(name)
                })
            }
    }

    override fun getTitle(arguments: Bundle?): String {
        return TITLE
    }
}
