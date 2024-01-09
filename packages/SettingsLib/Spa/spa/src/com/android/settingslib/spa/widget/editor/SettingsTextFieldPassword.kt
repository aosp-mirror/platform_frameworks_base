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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme

@Composable
fun SettingsTextFieldPassword(
    value: String,
    label: String,
    enabled: Boolean = true,
    onTextChange: (String) -> Unit,
) {
    var visibility by remember { mutableStateOf(false) }
    OutlinedTextField(
        modifier = Modifier
            .padding(SettingsDimension.menuFieldPadding)
            .fillMaxWidth(),
        value = value,
        onValueChange = onTextChange,
        label = { Text(text = label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Send
        ),
        enabled = enabled,
        trailingIcon = {
            Icon(
                imageVector = if (visibility) Icons.Outlined.VisibilityOff
                else Icons.Outlined.Visibility,
                contentDescription = "Visibility Icon",
                modifier = Modifier
                    .testTag("Visibility Icon")
                    .size(SettingsDimension.itemIconSize)
                    .toggleable(visibility) {
                        visibility = !visibility
                    },
            )
        },
        visualTransformation = if (visibility) VisualTransformation.None
        else PasswordVisualTransformation()
    )
}

@Preview
@Composable
private fun SettingsTextFieldPasswordPreview() {
    var value by remember { mutableStateOf("value") }
    SettingsTheme {
        SettingsTextFieldPassword(
            value = value,
            label = "label",
            onTextChange = { value = it },
        )
    }
}