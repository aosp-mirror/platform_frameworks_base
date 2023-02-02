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

package com.android.settingslib.spa.gallery.page

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.gallery.SettingsPageProviderEnum
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

object ArgumentPageProvider : SettingsPageProvider {
    // Defines all entry name in this page.
    // Note that entry name would be used in log. DO NOT change it once it is set.
    // One can still change the display name for better readability if necessary.
    private enum class EntryEnum(val displayName: String) {
        STRING_PARAM("string_param"),
        INT_PARAM("int_param"),
    }

    private fun createEntry(owner: SettingsPage, entry: EntryEnum): SettingsEntryBuilder {
        return SettingsEntryBuilder.create(owner, entry.name, entry.displayName)
    }

    override val name = SettingsPageProviderEnum.ARGUMENT.name
    override val displayName = SettingsPageProviderEnum.ARGUMENT.displayName
    override val parameter = ArgumentPageModel.parameter

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        if (!ArgumentPageModel.isValidArgument(arguments)) return emptyList()

        val owner = createSettingsPage(arguments)
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            createEntry(owner, EntryEnum.STRING_PARAM)
                // Set attributes
                .setIsSearchDataDynamic(true)
                .setSearchDataFn { ArgumentPageModel.genStringParamSearchData() }
                .setUiLayoutFn {
                    // Set ui rendering
                    Preference(ArgumentPageModel.create(it).genStringParamPreferenceModel())
                }.build()
        )

        entryList.add(
            createEntry(owner, EntryEnum.INT_PARAM)
                // Set attributes
                .setIsSearchDataDynamic(true)
                .setSearchDataFn { ArgumentPageModel.genIntParamSearchData() }
                .setUiLayoutFn {
                    // Set ui rendering
                    Preference(ArgumentPageModel.create(it).genIntParamPreferenceModel())
                }.build()
        )

        entryList.add(buildInjectEntry("foo")!!.setLink(fromPage = owner).build())
        entryList.add(buildInjectEntry("bar")!!.setLink(fromPage = owner).build())

        return entryList
    }

    fun buildInjectEntry(stringParam: String): SettingsEntryBuilder? {
        val arguments = ArgumentPageModel.buildArgument(stringParam)
        if (!ArgumentPageModel.isValidArgument(arguments)) return null

        return SettingsEntryBuilder.createInject(
            owner = createSettingsPage(arguments),
            displayName = "${name}_$stringParam",
        )
            .setSearchDataFn { ArgumentPageModel.genInjectSearchData() }
            .setUiLayoutFn {
                // Set ui rendering
                Preference(ArgumentPageModel.create(it).genInjectPreferenceModel())
            }
    }

    override fun getTitle(arguments: Bundle?): String {
        return ArgumentPageModel.genPageTitle()
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        val title = remember { getTitle(arguments) }
        val entries = remember { buildEntry(arguments) }
        val rtArgNext = remember { ArgumentPageModel.buildNextArgument(arguments) }
        RegularScaffold(title) {
            for (entry in entries) {
                if (entry.toPage != null) {
                    entry.UiLayout(rtArgNext)
                } else {
                    entry.UiLayout()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ArgumentPagePreview() {
    SpaEnvironmentFactory.resetForPreview()
    SettingsTheme {
        ArgumentPageProvider.Page(
            ArgumentPageModel.buildArgument(stringParam = "foo", intParam = 0)
        )
    }
}
