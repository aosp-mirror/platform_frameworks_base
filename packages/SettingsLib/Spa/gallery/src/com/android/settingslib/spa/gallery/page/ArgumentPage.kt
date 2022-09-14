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
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

object ArgumentPageProvider : SettingsPageProvider {
    override val name = ArgumentPageModel.name

    override val parameter = ArgumentPageModel.parameter

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        if (!ArgumentPageModel.isValidArgument(arguments)) return emptyList()

        val owner = SettingsPage.create(name, parameter, arguments)
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            SettingsEntryBuilder.create("string_param", owner)
                // Set attributes
                .setIsAllowSearch(true)
                .setUiLayoutFn {
                    // Set ui rendering
                    Preference(ArgumentPageModel.create(it).genStringParamPreferenceModel())
                }.build()
        )

        entryList.add(
            SettingsEntryBuilder.create("int_param", owner)
                // Set attributes
                .setIsAllowSearch(true)
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
            entryName = "${name}_$stringParam",
            owner = SettingsPage.create(name, parameter, arguments)
        )
            // Set attributes
            .setIsAllowSearch(false)
            .setUiLayoutFn {
                // Set ui rendering
                Preference(ArgumentPageModel.create(it).genInjectPreferenceModel())
            }
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(title = ArgumentPageModel.create(arguments).genPageTitle()) {
            for (entry in buildEntry(arguments)) {
                if (entry.name.startsWith(name)) {
                    entry.UiLayout(ArgumentPageModel.buildNextArgument(arguments))
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
    SettingsTheme {
        ArgumentPageProvider.Page(
            ArgumentPageModel.buildArgument(stringParam = "foo", intParam = 0)
        )
    }
}
