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

package com.android.settingslib.spa.widget.preference

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.ui.SettingsBody

@Composable
internal fun BasePreference(
    title: String,
    summary: () -> String,
    modifier: Modifier = Modifier,
    singleLineSummary: Boolean = false,
    icon: @Composable (() -> Unit)? = null,
    enabled: () -> Boolean = { true },
    paddingStart: Dp = SettingsDimension.itemPaddingStart,
    paddingEnd: Dp = SettingsDimension.itemPaddingEnd,
    paddingVertical: Dp = SettingsDimension.itemPaddingVertical,
    widget: @Composable () -> Unit = {},
) {
    BaseLayout(
        title = title,
        subTitle = {
            SettingsBody(
                body = summary(),
                maxLines = if (singleLineSummary) 1 else Int.MAX_VALUE,
            )
        },
        modifier = modifier,
        icon = icon,
        enabled = enabled,
        paddingStart = paddingStart,
        paddingEnd = paddingEnd,
        paddingVertical = paddingVertical,
        widget = widget,
    )
}

@Preview
@Composable
private fun BasePreferencePreview() {
    SettingsTheme {
        BasePreference(
            title = "Screen Saver",
            summary = { "Clock" },
        )
    }
}

@Preview
@Composable
private fun BasePreferenceIconPreview() {
    SettingsTheme {
        BasePreference(
            title = "Screen Saver",
            summary = { "Clock" },
            icon = {
                Icon(imageVector = Icons.Outlined.BatteryChargingFull, contentDescription = null)
            },
        )
    }
}
