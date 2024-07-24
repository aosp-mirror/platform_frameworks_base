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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spaprivileged.model.app.AppRecord

data class AppListItemModel<T : AppRecord>(
    val record: T,
    val label: String,
    val summary: () -> String,
)

@Composable
fun <T : AppRecord> AppListItemModel<T>.AppListItem(onClick: () -> Unit) {
    Preference(remember {
        object : PreferenceModel {
            override val title = label
            override val summary = this@AppListItem.summary
            override val icon = @Composable {
                AppIcon(app = record.app, size = SettingsDimension.appIconItemSize)
            }
            override val onClick = onClick
        }
    })
}

@Preview
@Composable
private fun AppListItemPreview() {
    SettingsTheme {
        val record = object : AppRecord {
            override val app = LocalContext.current.applicationInfo
        }
        AppListItemModel<AppRecord>(record, "Chrome", { "Allowed" }).AppListItem {}
    }
}
