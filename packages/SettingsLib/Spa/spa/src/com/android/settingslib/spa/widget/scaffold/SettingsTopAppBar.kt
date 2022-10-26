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

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.android.settingslib.spa.framework.compose.horizontalValues
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.rememberSettingsTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopAppBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    actions: @Composable RowScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    // TODO: Remove MaterialTheme() after top app bar color fixed in AndroidX.
    MaterialTheme(
        colorScheme = remember { colorScheme.copy(surface = colorScheme.background) },
        typography = rememberSettingsTypography(),
    ) {
        LargeTopAppBar(
            title = { Title(title) },
            navigationIcon = { NavigateBack() },
            actions = actions,
            colors = largeTopAppBarColors(),
            scrollBehavior = scrollBehavior,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
internal fun TopAppBarScrollBehavior.collapse() {
    with(state) {
        heightOffset = heightOffsetLimit
    }
}

@Composable
private fun Title(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .padding(WindowInsets.navigationBars.asPaddingValues().horizontalValues())
            .padding(SettingsDimension.itemPaddingAround),
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun largeTopAppBarColors() = TopAppBarDefaults.largeTopAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = SettingsTheme.colorScheme.surfaceHeader,
)
