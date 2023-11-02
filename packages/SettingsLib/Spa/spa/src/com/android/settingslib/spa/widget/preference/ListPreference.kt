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
import androidx.compose.runtime.IntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.dialog.SettingsDialog
import com.android.settingslib.spa.widget.ui.SettingsDialogItem

data class ListPreferenceOption(
    val id: Int,
    val text: String,
)

/**
 * The widget model for [ListPreference] widget.
 */
interface ListPreferenceModel {
    /**
     * The title of this [ListPreference].
     */
    val title: String

    /**
     * The icon of this [ListPreference].
     *
     * Default is `null` which means no icon.
     */
    val icon: (@Composable () -> Unit)?
        get() = null

    /**
     * Indicates whether this [ListPreference] is enabled.
     *
     * Disabled [ListPreference] will be displayed in disabled style.
     */
    val enabled: () -> Boolean
        get() = { true }

    val options: List<ListPreferenceOption>

    val selectedId: IntState

    val onIdSelected: (id: Int) -> Unit
}

@Composable
fun ListPreference(model: ListPreferenceModel) {
    var dialogOpened by rememberSaveable { mutableStateOf(false) }
    if (dialogOpened) {
        SettingsDialog(
            title = model.title,
            onDismissRequest = { dialogOpened = false },
        ) {
            Column(modifier = Modifier.selectableGroup()) {
                for (option in model.options) {
                    Radio(option, model.selectedId.intValue, model.enabled()) {
                        dialogOpened = false
                        model.onIdSelected(it)
                    }
                }
            }
        }
    }
    Preference(model = remember(model) {
        object : PreferenceModel {
            override val title = model.title
            override val summary = {
                model.options.find { it.id == model.selectedId.intValue }?.text ?: ""
            }
            override val icon = model.icon
            override val enabled = model.enabled
            override val onClick = { dialogOpened = true }.takeIf { model.options.isNotEmpty() }
        }
    })
}

@Composable
private fun Radio(
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
        Spacer(modifier = Modifier.width(SettingsDimension.itemPaddingEnd))
        SettingsDialogItem(text = option.text, enabled = enabled)
    }
}
