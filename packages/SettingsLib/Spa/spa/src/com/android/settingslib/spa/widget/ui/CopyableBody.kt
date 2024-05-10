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

package com.android.settingslib.spa.widget.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpOffset
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme

@Composable
fun CopyableBody(body: String) {
    var expanded by remember { mutableStateOf(false) }
    var dpOffset by remember { mutableStateOf(DpOffset.Unspecified) }

    Box(modifier = Modifier
        .fillMaxWidth()
        .pointerInput(Unit) {
            detectTapGestures(
                onLongPress = {
                    dpOffset = DpOffset(it.x.toDp(), it.y.toDp())
                    expanded = true
                },
            )
        }
    ) {
        SettingsBody(body)

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = dpOffset,
        ) {
            DropdownMenuTitle(body)
            DropdownMenuCopy(body) { expanded = false }
        }
    }
}

@Composable
private fun DropdownMenuTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .padding(MenuDefaults.DropdownMenuItemContentPadding)
            .padding(
                top = SettingsDimension.itemPaddingAround,
                bottom = SettingsDimension.buttonPaddingVertical,
            ),
        color = SettingsTheme.colorScheme.categoryTitle,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun DropdownMenuCopy(body: String, onCopy: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(android.R.string.copy),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        onClick = {
            onCopy()
            clipboardManager.setText(AnnotatedString(body))
        }
    )
}
