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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.android.settingslib.spa.framework.toState
import com.android.settingslib.spa.theme.SettingsDimension
import com.android.settingslib.spa.theme.SettingsOpacity
import com.android.settingslib.spa.theme.SettingsTheme

@Composable
internal fun BasePreference(
    title: String,
    summary: State<String>,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    enabled: State<Boolean> = true.toState(),
    paddingStart: Dp = SettingsDimension.itemPaddingStart,
    paddingEnd: Dp = SettingsDimension.itemPaddingEnd,
    paddingVertical: Dp = SettingsDimension.itemPaddingVertical,
    widget: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = paddingEnd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val alphaModifier =
            Modifier.alpha(if (enabled.value) SettingsOpacity.Full else SettingsOpacity.Disabled)
        if (icon != null) {
            Box(
                modifier = alphaModifier.size(SettingsDimension.itemIconContainerSize),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        } else {
            Spacer(modifier = Modifier.width(width = paddingStart))
        }
        TitleAndSummary(
            title = title,
            summary = summary,
            modifier = alphaModifier
                .weight(1f)
                .padding(vertical = paddingVertical),
        )
        widget()
    }
}

// Extracts a scope to avoid frequent recompose outside scope.
@Composable
private fun TitleAndSummary(title: String, summary: State<String>, modifier: Modifier) {
    Column(modifier) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        if (summary.value.isNotEmpty()) {
            Text(
                text = summary.value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview
@Composable
private fun BasePreferencePreview() {
    SettingsTheme {
        BasePreference(
            title = "Screen Saver",
            summary = "Clock".toState(),
        )
    }
}

@Preview
@Composable
private fun BasePreferenceIconPreview() {
    SettingsTheme {
        BasePreference(
            title = "Screen Saver",
            summary = "Clock".toState(),
            icon = {
                Icon(imageVector = Icons.Outlined.BatteryChargingFull, contentDescription = null)
            },
        )
    }
}
