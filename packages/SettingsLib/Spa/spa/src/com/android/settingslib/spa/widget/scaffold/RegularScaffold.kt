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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme

/**
 * A [Scaffold] which content is scrollable and wrapped in a [Column].
 *
 * For example, this is for the pages with some preferences and is scrollable when the items out of
 * the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegularScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = {
                    Text(
                        text = title,
                        modifier = Modifier.padding(SettingsDimension.itemPaddingAround),
                    )
                },
                navigationIcon = { NavigateUp() },
                actions = actions,
                colors = settingsTopAppBarColors(),
            )
        },
    ) { paddingValues ->
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.padding(paddingValues))
            content()
        }
    }
}

@Composable
internal fun settingsTopAppBarColors() = TopAppBarDefaults.largeTopAppBarColors(
    containerColor = SettingsTheme.colorScheme.surfaceHeader,
    scrolledContainerColor = SettingsTheme.colorScheme.surfaceHeader,
)

@Preview
@Composable
private fun RegularScaffoldPreview() {
    SettingsTheme {
        RegularScaffold(title = "Display") {}
    }
}
