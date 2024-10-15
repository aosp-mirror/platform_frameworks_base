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

package com.android.settingslib.spa.widget.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.isSpaExpressiveEnabled
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

/** A category title that is placed before a group of similar items. */
@Composable
fun CategoryTitle(title: String) {
    Text(
        text = title,
        modifier =
            Modifier.padding(
                start = SettingsDimension.itemPaddingStart,
                top = 20.dp,
                end =
                    if (isSpaExpressiveEnabled) SettingsDimension.paddingSmall
                    else SettingsDimension.itemPaddingEnd,
                bottom = 8.dp,
            ),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
    )
}

/**
 * A container that is used to group similar items. A [Category] displays a [CategoryTitle] and
 * visually separates groups of items.
 */
@Composable
fun Category(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            if (isSpaExpressiveEnabled)
                Modifier.padding(
                    horizontal = SettingsDimension.paddingLarge,
                    vertical = SettingsDimension.paddingSmall,
                )
            else Modifier
    ) {
        var displayTitle by remember { mutableStateOf(false) }
        if (title != null && displayTitle) CategoryTitle(title = title)
        Column(
            modifier =
                Modifier.onGloballyPositioned { coordinates ->
                        displayTitle = coordinates.size.height > 0
                    }
                    .then(
                        if (isSpaExpressiveEnabled)
                            Modifier.fillMaxWidth().clip(SettingsShape.CornerMedium2)
                        else Modifier
                    ),
            verticalArrangement =
                if (isSpaExpressiveEnabled) Arrangement.spacedBy(SettingsDimension.paddingTiny)
                else Arrangement.Top,
            content = content,
        )
    }
}

@Preview
@Composable
private fun CategoryPreview() {
    SettingsTheme {
        Category("Appearance") {
            Preference(
                object : PreferenceModel {
                    override val title = "Title"
                    override val summary = { "Summary" }
                }
            )
            Preference(
                object : PreferenceModel {
                    override val title = "Title"
                    override val summary = { "Summary" }
                    override val icon =
                        @Composable { SettingsIcon(imageVector = Icons.Outlined.TouchApp) }
                }
            )
        }
    }
}
