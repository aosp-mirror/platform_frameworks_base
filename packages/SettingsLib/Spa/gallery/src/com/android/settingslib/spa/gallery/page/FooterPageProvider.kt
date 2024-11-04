/*
 * Copyright (C) 2023 The Android Open Source Project
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
import com.android.settingslib.spa.framework.common.EntrySearchData
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.gallery.R
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.AnnotatedText
import com.android.settingslib.spa.widget.ui.Footer

private const val TITLE = "Sample Footer"

object FooterPageProvider : SettingsPageProvider {
    override val name = "Footer"
    private val owner = createSettingsPage()

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            SettingsEntryBuilder.create("Some Preference", owner)
                .setSearchDataFn { EntrySearchData(title = "Some Preference") }
                .setUiLayoutFn {
                    Preference(remember {
                        object : PreferenceModel {
                            override val title = "Some Preference"
                            override val summary = { "Some summary" }
                        }
                    })
                }.build()
        )

        return entryList
    }

    @Composable
    fun Entry() {
        Preference(object : PreferenceModel {
            override val title = TITLE
            override val onClick = navigator(name)
        })
    }

    override fun getTitle(arguments: Bundle?): String {
        return TITLE
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(title = getTitle(arguments)) {
            for (entry in buildEntry(arguments)) {
                entry.UiLayout()
            }
            Footer(footerText = "Footer text always at the end of page.")
            Footer {
                AnnotatedText(R.string.footer_with_two_links)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FooterPagePreview() {
    SettingsTheme {
        FooterPageProvider.Page(null)
    }
}
