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

package com.android.settingslib.spa.widget.preference

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsDimension

@Composable
fun IntroPreference(
    title: String,
    descriptions: List<String>? = null,
    imageVector: ImageVector? = null,
) {
    IntroPreference(title = title, descriptions = descriptions, icon = { IntroIcon(imageVector) })
}

@Composable
fun IntroAppPreference(
    title: String,
    descriptions: List<String>? = null,
    appIcon: @Composable (() -> Unit),
) {
    IntroPreference(title = title, descriptions = descriptions, icon = { IntroAppIcon(appIcon) })
}

@Composable
internal fun IntroPreference(
    title: String,
    descriptions: List<String>?,
    icon: @Composable (() -> Unit),
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = SettingsDimension.paddingExtraLarge,
                    vertical = SettingsDimension.paddingLarge,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        IntroTitle(title)
        IntroDescription(descriptions)
    }
}

@Composable
private fun IntroIcon(imageVector: ImageVector?) {
    if (imageVector != null) {
        Box(
            modifier =
                Modifier.size(SettingsDimension.itemIconContainerSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                modifier = Modifier.size(SettingsDimension.introIconSize),
                tint = MaterialTheme.colorScheme.onSecondary,
            )
        }
    }
}

@Composable
private fun IntroAppIcon(appIcon: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(SettingsDimension.itemIconContainerSize).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        appIcon()
    }
}

@Composable
private fun IntroTitle(title: String) {
    Box(modifier = Modifier.padding(top = SettingsDimension.paddingLarge)) {
        Text(
            text = title,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun IntroDescription(descriptions: List<String>?) {
    if (descriptions != null) {
        for (description in descriptions) {
            if (description.isEmpty()) continue
            Text(
                text = description,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = SettingsDimension.paddingExtraSmall),
            )
        }
    }
}

@Preview
@Composable
private fun IntroPreferencePreview() {
    IntroPreference(
        title = "Preferred network type",
        descriptions = listOf("Description", "Version"),
        imageVector = Icons.Outlined.AirplanemodeActive,
    )
}
