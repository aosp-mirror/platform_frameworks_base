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

package com.android.settingslib.spa.widget.preference

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.compose.thenIf
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.isSpaExpressiveEnabled
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.SettingsListItem

@Composable
fun RadioPreferences(model: ListPreferenceModel) {
    Category(modifier = Modifier.selectableGroup(), title = model.title) {
        for (option in model.options) {
            Radio2(option, model.selectedId.intValue, model.enabled()) {
                model.onIdSelected(it)
            }
        }
    }
}

@Composable
fun Radio2(
    option: ListPreferenceOption,
    selectedId: Int,
    enabled: Boolean,
    onIdSelected: (id: Int) -> Unit,
) {
    val selected = option.id == selectedId
    val surfaceBright = MaterialTheme.colorScheme.surfaceBright
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .thenIf(isSpaExpressiveEnabled) {
                    Modifier.heightIn(min = SettingsDimension.preferenceMinHeight)
                        .background(surfaceBright)
                }
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    onClick = { onIdSelected(option.id) },
                    role = Role.RadioButton,
                )
                .then(
                    if (isSpaExpressiveEnabled) Modifier.padding(SettingsDimension.itemPadding)
                    else Modifier.padding(SettingsDimension.dialogItemPadding)
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(
            modifier =
                Modifier.width(
                    if (isSpaExpressiveEnabled) SettingsDimension.paddingExtraSmall6
                    else SettingsDimension.itemDividerHeight
                )
        )
        SettingsListItem(text = option.text, enabled = enabled)
    }
}

@Preview
@Composable
private fun RadioPreferencePreview() {
    RadioPreferences(
        object : ListPreferenceModel {
            override val title: String = "Title"
            override val options: List<ListPreferenceOption> =
                listOf(
                    ListPreferenceOption(id = 0, text = "option1"),
                    ListPreferenceOption(id = 1, text = "option2"),
                    ListPreferenceOption(id = 2, text = "option3"),
                )
            override val selectedId: IntState = remember { mutableIntStateOf(0) }
            override val onIdSelected: (Int) -> Unit = {}
        }
    )
}
