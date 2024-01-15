/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

data class AlertDialogButton(
    val text: String,
    val onClick: () -> Unit = {},
)

interface AlertDialogPresenter {
    /** Opens the dialog. */
    fun open()

    /** Closes the dialog. */
    fun close()
}

@Composable
fun rememberAlertDialogPresenter(
    confirmButton: AlertDialogButton? = null,
    dismissButton: AlertDialogButton? = null,
    title: String? = null,
    text: @Composable (() -> Unit)? = null,
): AlertDialogPresenter {
    var openDialog by rememberSaveable { mutableStateOf(false) }
    val alertDialogPresenter = remember {
        object : AlertDialogPresenter {
            override fun open() {
                openDialog = true
            }

            override fun close() {
                openDialog = false
            }
        }
    }
    if (openDialog) {
        alertDialogPresenter.SettingsAlertDialog(confirmButton, dismissButton, title, text)
    }
    return alertDialogPresenter
}

@Composable
private fun AlertDialogPresenter.SettingsAlertDialog(
    confirmButton: AlertDialogButton?,
    dismissButton: AlertDialogButton?,
    title: String?,
    text: @Composable (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = ::close,
        modifier = Modifier.width(getDialogWidth()),
        confirmButton = { confirmButton?.let { Button(it) } },
        dismissButton = dismissButton?.let { { Button(it) } },
        title = title?.let { { Text(it) } },
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

@Composable
fun getDialogWidth(): Dp {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp.dp * when (configuration.orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> 0.6f
        else -> 0.8f
    }
}

@Composable
private fun AlertDialogPresenter.Button(button: AlertDialogButton) {
    TextButton(
        onClick = {
            close()
            button.onClick()
        },
    ) {
        Text(button.text)
    }
}
