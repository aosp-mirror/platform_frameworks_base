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

package com.android.settingslib.spa.widget.dialog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.navigation.compose.NavHost
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.widget.ui.SettingsTitle

@Composable
fun SettingsDialog(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        SettingsDialogCard(title, content)
    }
}

/**
 * Card for dialog, suitable for independent dialog in the [NavHost].
 */
@Composable
fun SettingsDialogCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        shape = SettingsShape.CornerExtraLarge,
        colors = CardDefaults.cardColors(containerColor = AlertDialogDefaults.containerColor),
    ) {
        Column(modifier = Modifier.padding(vertical = SettingsDimension.itemPaddingAround)) {
            Box(modifier = Modifier.padding(SettingsDimension.dialogItemPadding)) {
                SettingsTitle(title = title, useMediumWeight = true)
            }
            content()
        }
    }
}
