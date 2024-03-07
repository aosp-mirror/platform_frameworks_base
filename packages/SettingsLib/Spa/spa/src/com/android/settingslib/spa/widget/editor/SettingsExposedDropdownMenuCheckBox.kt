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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsExposedDropdownMenuCheckBox(
    label: String,
    options: List<String>,
    selectedOptionsState: SnapshotStateList<Int>,
    emptyVal: String = "",
    enabled: Boolean,
    onSelectedOptionStateChange: () -> Unit,
) {
    var dropDownWidth by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .width(350.dp)
            .padding(SettingsDimension.itemPadding)
            .onSizeChanged { dropDownWidth = it.width },
    ) {
        OutlinedTextField(
            // The `menuAnchor` modifier must be passed to the text field for correctness.
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            value = if (selectedOptionsState.size == 0) emptyVal
                    else selectedOptionsState.joinToString { options[it] },
            onValueChange = {},
            label = { Text(text = label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded
                )
            },
            readOnly = true,
            enabled = enabled
        )
        if (options.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                modifier = Modifier
                    .fillMaxWidth()
                    .width(with(LocalDensity.current) { dropDownWidth.toDp() }),
                onDismissRequest = { expanded = false },
            ) {
                options.forEachIndexed { index, option ->
                    TextButton(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(),
                        onClick = {
                            if (selectedOptionsState.contains(index)) {
                                selectedOptionsState.remove(
                                    index
                                )
                            } else {
                                selectedOptionsState.add(
                                    index
                                )
                            }
                            onSelectedOptionStateChange()
                        }) {
                        Row(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedOptionsState.contains(index),
                                onCheckedChange = null,
                            )
                            Text(text = option)
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun ActionButtonsPreview() {
    val options = listOf("item1", "item2", "item3")
    val selectedOptionsState = remember { mutableStateListOf(0, 1) }
    SettingsTheme {
        SettingsExposedDropdownMenuCheckBox(
            label = "label",
            options = options,
            selectedOptionsState = selectedOptionsState,
            enabled = true,
            onSelectedOptionStateChange = {})
    }
}