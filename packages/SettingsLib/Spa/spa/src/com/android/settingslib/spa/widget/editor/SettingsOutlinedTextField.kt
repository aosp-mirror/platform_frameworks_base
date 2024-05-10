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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme

@Composable
fun SettingsOutlinedTextField(
    value: String,
    label: String,
    errorMessage: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    onTextChange: (String) -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SettingsDimension.itemPadding),
        value = value,
        onValueChange = onTextChange,
        label = {
            Text(text = label)
        },
        singleLine = singleLine,
        enabled = enabled,
        isError = errorMessage != null,
        supportingText = {
            if (errorMessage != null) {
                Text(text = errorMessage)
            }
        }
    )
}

@Preview
@Composable
private fun SettingsOutlinedTextFieldPreview() {
    var value by remember { mutableStateOf("Enabled Value") }
    SettingsTheme {
        SettingsOutlinedTextField(
            value = value,
            label = "OutlinedTextField Enabled",
            enabled = true,
            onTextChange = {value = it})
    }
}