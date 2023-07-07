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

package com.android.settingslib.spaprivileged.template.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.android.settingslib.spa.widget.preference.TwoTargetButtonPreference

@Composable
fun <T : AppRecord> AppListItemModel<T>.AppListButtonItem (
    onClick: () -> Unit,
    onButtonClick: () -> Unit,
    buttonIcon: ImageVector,
    buttonIconDescription: String,
) {
        TwoTargetButtonPreference(
                title = label,
                summary = this@AppListButtonItem.summary,
                icon = { AppIcon(record.app, SettingsDimension.appIconItemSize) },
                onClick = onClick,
                buttonIcon = buttonIcon,
                buttonIconDescription = buttonIconDescription,
                onButtonClick = onButtonClick
        )
}
