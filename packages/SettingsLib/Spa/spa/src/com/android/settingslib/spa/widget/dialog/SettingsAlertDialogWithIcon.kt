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

package com.android.settingslib.spa.widget.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties

@Composable
fun SettingsAlertDialogWithIcon(
    onDismissRequest: () -> Unit,
    confirmButton: AlertDialogButton?,
    dismissButton: AlertDialogButton?,
    title: String?,
    icon: @Composable (() -> Unit)? = {
        Icon(
            Icons.Default.WarningAmber,
            contentDescription = null
        )
    },
    text: @Composable (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = icon,
        modifier = Modifier.width(getDialogWidth()),
        confirmButton = {
            confirmButton?.let {
                Button(
                    onClick = {
                        it.onClick()
                    },
                ) {
                    Text(it.text)
                }
            }
        },
        dismissButton = dismissButton?.let {
            {
                OutlinedButton(
                    onClick = {
                        it.onClick()
                    },
                ) {
                    Text(it.text)
                }
            }
        },
        title = title?.let {
            {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        },
        text = text?.let {
            {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    text()
                }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}