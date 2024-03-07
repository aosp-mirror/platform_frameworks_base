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

package com.android.settingslib.spa.widget.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsOpacity.alphaForEnabled
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.toMediumWeight

@Composable
fun SettingsTitle(title: String, useMediumWeight: Boolean = false) {
    Text(
        text = title,
        modifier = Modifier.padding(vertical = SettingsDimension.paddingTiny),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium.withWeight(useMediumWeight),
    )
}

@Composable
fun SettingsTitleSmall(title: String, useMediumWeight: Boolean = false) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleSmall.withWeight(useMediumWeight),
    )
}

@Composable
fun SettingsDialogItem(text: String, enabled: Boolean = true) {
    Text(
        text = text,
        modifier = Modifier.alphaForEnabled(enabled),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun SettingsBody(
    body: String,
    maxLines: Int = Int.MAX_VALUE,
) {
    if (body.isNotEmpty()) {
        Text(
            text = body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = maxLines,
        )
    }
}

@Composable
fun PlaceholderTitle(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(SettingsDimension.itemPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

private fun TextStyle.withWeight(useMediumWeight: Boolean = false) = when (useMediumWeight) {
    true -> toMediumWeight()
    else -> this
}

@Preview
@Composable
private fun BasePreferencePreview() {
    SettingsTheme {
        Column(Modifier.width(100.dp)) {
            SettingsTitle(
                title = "Title",
            )
            SettingsBody(
                body = "Long long long long long long text",
            )
            SettingsBody(
                body = "Long long long long long long text",
                maxLines = 1,
            )
        }
    }
}
