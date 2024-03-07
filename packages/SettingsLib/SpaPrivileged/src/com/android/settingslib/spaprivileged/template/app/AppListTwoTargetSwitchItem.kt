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

package com.android.settingslib.spaprivileged.template.app

import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.preference.TwoTargetSwitchPreference
import com.android.settingslib.spaprivileged.model.app.AppRecord

@Composable
fun <T : AppRecord> AppListItemModel<T>.AppListTwoTargetSwitchItem(
    onClick: () -> Unit,
    checked: () -> Boolean?,
    changeable: () -> Boolean,
    onCheckedChange: ((newChecked: Boolean) -> Unit)?,
) {
    TwoTargetSwitchPreference(
        model = object : SwitchPreferenceModel {
            override val title = label
            override val summary = this@AppListTwoTargetSwitchItem.summary
            override val checked = checked
            override val changeable = changeable
            override val onCheckedChange = onCheckedChange
        },
        icon = { AppIcon(record.app, SettingsDimension.appIconItemSize) },
        onClick = onClick,
    )
}
