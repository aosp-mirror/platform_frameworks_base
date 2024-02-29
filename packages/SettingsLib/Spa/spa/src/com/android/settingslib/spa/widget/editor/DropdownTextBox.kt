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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.SettingsDimension

internal interface DropdownTextBoxScope {
    fun dismiss()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DropdownTextBox(
    label: String,
    text: String,
    enabled: Boolean = true,
    errorMessage: String? = null,
    content: @Composable DropdownTextBoxScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val scope = remember {
        object : DropdownTextBoxScope {
            override fun dismiss() {
                expanded = false
            }
        }
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = enabled && it },
        modifier = Modifier
            .padding(SettingsDimension.menuFieldPadding)
            .width(Width),
    ) {
        OutlinedTextField(
            // The `menuAnchor` modifier must be passed to the text field for correctness.
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            value = text,
            onValueChange = { },
            label = { Text(text = label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            readOnly = true,
            enabled = enabled,
            isError = errorMessage != null,
            supportingText = errorMessage?.let { { Text(text = it) } },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            modifier = Modifier.width(Width),
            onDismissRequest = { expanded = false },
        ) { scope.content() }
    }
}

private val Width = 310.dp
