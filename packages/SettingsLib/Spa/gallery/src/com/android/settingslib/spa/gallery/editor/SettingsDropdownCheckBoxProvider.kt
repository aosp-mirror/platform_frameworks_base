/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.spa.gallery.editor

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.editor.SettingsDropdownCheckBox
import com.android.settingslib.spa.widget.editor.SettingsDropdownCheckOption
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

private const val TITLE = "Sample SettingsDropdownCheckBox"

object SettingsDropdownCheckBoxProvider : SettingsPageProvider {
    override val name = "SettingsDropdownCheckBox"

    override fun getTitle(arguments: Bundle?): String {
        return TITLE
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(title = TITLE) {
            SettingsDropdownCheckBox(
                label = "SettingsDropdownCheckBox",
                options = remember {
                    listOf(
                        SettingsDropdownCheckOption("Item 1"),
                        SettingsDropdownCheckOption("Item 2"),
                        SettingsDropdownCheckOption("Item 3"),
                    )
                },
            )
            SettingsDropdownCheckBox(
                label = "Empty list",
                options = emptyList(),
            )
            SettingsDropdownCheckBox(
                label = "Disabled",
                options = remember {
                    listOf(
                        SettingsDropdownCheckOption("Item 1", selected = mutableStateOf(true)),
                        SettingsDropdownCheckOption("Item 2"),
                        SettingsDropdownCheckOption("Item 3"),
                    )
                },
                enabled = false,
            )
            SettingsDropdownCheckBox(
                label = "With disabled item",
                options = remember {
                    listOf(
                        SettingsDropdownCheckOption("Enabled item 1"),
                        SettingsDropdownCheckOption("Enabled item 2"),
                        SettingsDropdownCheckOption(
                            text = "Disabled item 1",
                            changeable = false,
                            selected = mutableStateOf(true),
                        ),
                        SettingsDropdownCheckOption("Disabled item 2", changeable = false),
                    )
                },
            )
            SettingsDropdownCheckBox(
                label = "With select all",
                options = remember {
                    listOf(
                        SettingsDropdownCheckOption("All", isSelectAll = true),
                        SettingsDropdownCheckOption("Item 1"),
                        SettingsDropdownCheckOption("Item 2"),
                        SettingsDropdownCheckOption("Item 3"),
                    )
                },
            )
            SettingsDropdownCheckBox(
                label = "With disabled item and select all",
                options =
                remember {
                    listOf(
                        SettingsDropdownCheckOption("All", isSelectAll = true, changeable = false),
                        SettingsDropdownCheckOption("Enabled item 1"),
                        SettingsDropdownCheckOption("Enabled item 2"),
                        SettingsDropdownCheckOption(
                            text = "Disabled item 1",
                            changeable = false,
                            selected = mutableStateOf(true),
                        ),
                        SettingsDropdownCheckOption("Disabled item 2", changeable = false),
                    )
                },
            )
        }
    }

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner = createSettingsPage()).setUiLayoutFn {
            Preference(object : PreferenceModel {
                override val title = TITLE
                override val onClick = navigator(name)
            })
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsDropdownCheckBoxPagePreview() {
    SettingsTheme {
        SettingsDropdownCheckBoxProvider.Page(null)
    }
}
