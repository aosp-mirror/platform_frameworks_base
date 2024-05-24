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

package com.android.settingslib.spa.widget.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsOpacity.alphaForEnabled
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.editor.SettingsDropdownCheckOption.Companion.changeable

data class SettingsDropdownCheckOption(
    /** The displayed text of this option. */
    val text: String,

    /** If true, check / uncheck this item will check / uncheck all enabled options. */
    val isSelectAll: Boolean = false,

    /** If not changeable, cannot check or uncheck this option. */
    val changeable: Boolean = true,

    /** The selected state of this option. */
    val selected: MutableState<Boolean> = mutableStateOf(false),

    /** Get called when the option is clicked, no matter if it's changeable. */
    val onClick: () -> Unit = {},
) {
    companion object {
        val List<SettingsDropdownCheckOption>.changeable: Boolean
            get() = filter { !it.isSelectAll }.any { it.changeable }
    }
}

@Composable
fun SettingsDropdownCheckBox(
    label: String,
    options: List<SettingsDropdownCheckOption>,
    emptyText: String = "",
    enabled: Boolean = true,
    errorMessage: String? = null,
    onSelectedStateChange: () -> Unit = {},
) {
    DropdownTextBox(
        label = label,
        text = getDisplayText(options) ?: emptyText,
        enabled = enabled && options.changeable,
        errorMessage = errorMessage,
    ) {
        for (option in options) {
            CheckboxItem(option) {
                option.onClick()
                if (option.changeable) {
                    checkboxItemOnClick(options, option)
                    onSelectedStateChange()
                }
            }
        }
    }
}

private fun getDisplayText(options: List<SettingsDropdownCheckOption>): String? {
    val selectedOptions = options.filter { it.selected.value }
    if (selectedOptions.isEmpty()) return null
    return selectedOptions.filter { it.isSelectAll }.ifEmpty { selectedOptions }
        .joinToString { it.text }
}

private fun checkboxItemOnClick(
    options: List<SettingsDropdownCheckOption>,
    clickedOption: SettingsDropdownCheckOption,
) {
    if (!clickedOption.changeable) return
    val newChecked = !clickedOption.selected.value
    if (clickedOption.isSelectAll) {
        for (option in options.filter { it.changeable }) option.selected.value = newChecked
    } else {
        clickedOption.selected.value = newChecked
    }
    val (selectAllOptions, regularOptions) = options.partition { it.isSelectAll }
    val isAllRegularOptionsChecked = regularOptions.all { it.selected.value }
    selectAllOptions.forEach { it.selected.value = isAllRegularOptionsChecked }
}

@Composable
private fun CheckboxItem(
    option: SettingsDropdownCheckOption,
    onClick: (SettingsDropdownCheckOption) -> Unit,
) {
    TextButton(
        onClick = { onClick(option) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SettingsDimension.itemPaddingAround),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = option.selected.value,
                onCheckedChange = null,
                enabled = option.changeable,
            )
            Text(text = option.text, modifier = Modifier.alphaForEnabled(option.changeable))
        }
    }
}

@Preview
@Composable
private fun ActionButtonsPreview() {
    val item1 = SettingsDropdownCheckOption("item1")
    val item2 = SettingsDropdownCheckOption("item2")
    val item3 = SettingsDropdownCheckOption("item3")
    val options = listOf(item1, item2, item3)
    SettingsTheme {
        SettingsDropdownCheckBox(
            label = "label",
            options = options,
        )
    }
}
