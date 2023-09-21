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

package com.android.settingslib.spa.gallery.editor

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.editor.SettingsExposedDropdownMenuBox
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold

private const val TITLE = "Sample SettingsExposedDropdownMenuBox"

object SettingsExposedDropdownMenuBoxPageProvider : SettingsPageProvider {
    override val name = "SettingsExposedDropdownMenuBox"
    private const val exposedDropdownMenuBoxLabel = "ExposedDropdownMenuBoxLabel"

    override fun getTitle(arguments: Bundle?): String {
        return TITLE
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        var selectedItem by remember { mutableIntStateOf(-1) }
        val options = listOf("item1", "item2", "item3")
        RegularScaffold(title = TITLE) {
            SettingsExposedDropdownMenuBox(
                label = exposedDropdownMenuBoxLabel,
                options = options,
                selectedOptionIndex = selectedItem,
                enabled = true,
                onselectedOptionTextChange = { selectedItem = it })
        }
    }

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner = createSettingsPage())
            .setUiLayoutFn {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val onClick = navigator(name)
                })
            }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsExposedDropdownMenuBoxPagePreview() {
    SettingsTheme {
        SettingsExposedDropdownMenuBoxPageProvider.Page(null)
    }
}
