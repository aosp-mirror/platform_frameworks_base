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

package com.android.settingslib.spa.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.settingslib.spa.framework.common.LogCategory
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.dialog.getDialogWidth


class SpaDialogActivity : ComponentActivity() {
    private val spaEnvironment get() = SpaEnvironmentFactory.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spaEnvironment.logger.message(TAG, "onCreate", category = LogCategory.FRAMEWORK)
        setContent {
            SettingsTheme {
                Content()
            }
        }
    }

    @Composable
    fun Content() {
        var openAlertDialog by remember { mutableStateOf(false) }
        AlertDialog(openAlertDialog)
        LaunchedEffect(key1 = Unit) {
            openAlertDialog = true
        }
    }

    @Composable
    fun AlertDialog(openAlertDialog: Boolean) {
        when {
            openAlertDialog -> {
                AlertDialogExample(
                    onDismissRequest = { finish() },
                    onConfirmation = { finish() },
                    dialogTitle = intent.getStringExtra(DIALOG_TITLE) ?: DIALOG_TITLE,
                    dialogText = intent.getStringExtra(DIALOG_TEXT) ?: DIALOG_TEXT,
                    icon = Icons.Default.WarningAmber
                )
            }
        }
    }

    @Composable
    fun AlertDialogExample(
        onDismissRequest: () -> Unit,
        onConfirmation: () -> Unit,
        dialogTitle: String,
        dialogText: String,
        icon: ImageVector,
    ) {
        AlertDialog(
            modifier = Modifier.width(getDialogWidth()),
            icon = {
                Icon(icon, contentDescription = null)
            },
            title = {
                Text(text = dialogTitle)
            },
            text = {
                Text(text = dialogText)
            },
            onDismissRequest = {
                onDismissRequest()
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        onDismissRequest()
                    }
                ) {
                    Text(intent.getStringExtra(DISMISS_TEXT) ?: DISMISS_TEXT)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirmation()
                    },
                ) {
                    Text(intent.getStringExtra(CONFIRM_TEXT) ?: CONFIRM_TEXT)
                }
            }
        )
    }

    companion object {
        private const val TAG = "SpaDialogActivity"
        private const val DIALOG_TITLE = "dialogTitle"
        private const val DIALOG_TEXT = "dialogText"
        private const val CONFIRM_TEXT = "confirmText"
        private const val DISMISS_TEXT = "dismissText"
    }
}
