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

package com.android.settingslib.spa.gallery.dialog

import android.os.Bundle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.widget.dialog.AlertDialogButton
import com.android.settingslib.spa.widget.dialog.SettingsAlertDialogWithIcon
import com.android.settingslib.spa.widget.dialog.rememberAlertDialogPresenter
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category

private const val TITLE = "Category: Dialog"

object DialogMainPageProvider : SettingsPageProvider {
    override val name = "DialogMain"

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(TITLE) {
            Category {
                AlertDialog()
                AlertDialogWithIcon()
                NavDialog()
            }
        }
    }

    @Composable
    fun Entry() {
        Preference(
            object : PreferenceModel {
                override val title = TITLE
                override val onClick = navigator(name)
            }
        )
    }

    override fun getTitle(arguments: Bundle?) = TITLE
}

@Composable
private fun AlertDialog() {
    val alertDialogPresenter =
        rememberAlertDialogPresenter(
            confirmButton = AlertDialogButton("Ok"),
            dismissButton = AlertDialogButton("Cancel"),
            title = "Title",
            text = { Text("Text") },
        )
    Preference(
        object : PreferenceModel {
            override val title = "Show AlertDialog"
            override val onClick = alertDialogPresenter::open
        }
    )
}

@Composable
private fun AlertDialogWithIcon() {
    var openDialog by rememberSaveable { mutableStateOf(false) }
    val close = { openDialog = false }
    val open = { openDialog = true }
    if (openDialog) {
        SettingsAlertDialogWithIcon(
            title = "Title",
            onDismissRequest = close,
            confirmButton = AlertDialogButton("OK", onClick = close),
            dismissButton = AlertDialogButton("Dismiss", onClick = close),
        ) {}
    }
    Preference(
        object : PreferenceModel {
            override val title = "Show AlertDialogWithIcon"
            override val onClick = open
        }
    )
}

@Composable
private fun NavDialog() {
    Preference(
        object : PreferenceModel {
            override val title = "Navigate to Dialog"
            override val onClick = navigator(route = NavDialogProvider.name)
        }
    )
}
