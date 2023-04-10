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

package com.android.settingslib.spa.gallery.ui

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.EntrySearchData
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SimplePreferenceMacro
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.CategoryTitle

private const val TITLE = "Sample Category"

object CategoryPageProvider : SettingsPageProvider {
    override val name = "Category"
    private val owner = createSettingsPage()

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner)
            .setUiLayoutFn {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val onClick = navigator(name)
                })
            }
            .setSearchDataFn { EntrySearchData(title = TITLE) }
    }

    override fun getTitle(arguments: Bundle?): String {
        return TITLE
    }

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            SettingsEntryBuilder.create("Preference 1", owner)
                .setMacro { SimplePreferenceMacro(title = "Preference 1", summary = "Summary 1") }
                .build()
        )
        entryList.add(
            SettingsEntryBuilder.create("Preference 2", owner)
                .setMacro { SimplePreferenceMacro(title = "Preference 2", summary = "Summary 2") }
                .build()
        )
        entryList.add(
            SettingsEntryBuilder.create("Preference 3", owner)
                .setMacro { SimplePreferenceMacro(title = "Preference 2", summary = "Summary 3") }
                .build()

        )
        entryList.add(
            SettingsEntryBuilder.create("Preference 4", owner)
                .setMacro { SimplePreferenceMacro(title = "Preference 4", summary = "Summary 4") }
                .build()
        )
        return entryList
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        val entries = buildEntry(arguments)
        RegularScaffold(title = getTitle(arguments)) {
            CategoryTitle("Category A")
            entries[0].UiLayout()
            entries[1].UiLayout()

            Category("Category B") {
                entries[2].UiLayout()
                entries[3].UiLayout()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SpinnerPagePreview() {
    SettingsTheme {
        SpinnerPageProvider.Page(null)
    }
}
