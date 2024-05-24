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

package com.android.settingslib.spa.widget.editor

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsTheme

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsDropdownBox(
    label: String,
    options: List<String>,
    selectedOptionIndex: Int,
    enabled: Boolean = true,
    onSelectedOptionChange: (Int) -> Unit,
) {
    DropdownTextBox(
        label = label,
        text = options.getOrElse(selectedOptionIndex) { "" },
        enabled = enabled && options.isNotEmpty(),
    ) {
        options.forEachIndexed { index, option ->
            DropdownMenuItem(
                text = { Text(option) },
                onClick = {
                    dismiss()
                    onSelectedOptionChange(index)
                },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
        }
    }
}

@Preview
@Composable
private fun SettingsDropdownBoxPreview() {
    val item1 = "item1"
    val item2 = "item2"
    val item3 = "item3"
    val options = listOf(item1, item2, item3)
    SettingsTheme {
        SettingsDropdownBox(
            label = "ExposedDropdownMenuBoxLabel",
            options = options,
            selectedOptionIndex = 0,
            enabled = true,
        ) {}
    }
}
