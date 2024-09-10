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

package com.android.settingslib.spa.widget.scaffold

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsTheme

@Composable
internal fun SettingsTab(
    title: String,
    selected: Boolean,
    currentPageOffset: Float,
    onClick: () -> Unit,
) {
    // Shows a color transition during pager scroll.
    // 0f -> Selected, 1f -> Not selected
    val colorFraction = if (selected) (currentPageOffset * 2).coerceAtMost(1f) else 1f
    Tab(
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(SettingsShape.CornerMedium)
            .background(
                color = lerp(
                    start = MaterialTheme.colorScheme.primaryContainer,
                    stop = MaterialTheme.colorScheme.surface,
                    fraction = colorFraction,
                ),
            ),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = lerp(
                start = MaterialTheme.colorScheme.onPrimaryContainer,
                stop = MaterialTheme.colorScheme.onSurface,
                fraction = colorFraction,
            ),
        )
    }
}

@Preview
@Composable
fun SettingsTabPreview() {
    SettingsTheme {
        Column {
            SettingsTab(title = "Personal", selected = true, currentPageOffset = 0f) {}
            SettingsTab(title = "Work", selected = false, currentPageOffset = 0f) {}
        }
    }
}
