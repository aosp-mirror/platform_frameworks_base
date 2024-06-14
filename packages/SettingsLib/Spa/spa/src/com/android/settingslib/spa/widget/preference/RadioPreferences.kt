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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.ui.CategoryTitle
import com.android.settingslib.spa.widget.ui.SettingsListItem

@Composable
fun RadioPreferences(model: ListPreferenceModel) {
    CategoryTitle(title = model.title)
    Spacer(modifier = Modifier.width(SettingsDimension.itemDividerHeight))
    Column(modifier = Modifier.selectableGroup()) {
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = { onIdSelected(option.id) },
                role = Role.RadioButton,
            )
            .padding(SettingsDimension.dialogItemPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(modifier = Modifier.width(SettingsDimension.itemDividerHeight))
        SettingsListItem(text = option.text, enabled = enabled)
    }
}