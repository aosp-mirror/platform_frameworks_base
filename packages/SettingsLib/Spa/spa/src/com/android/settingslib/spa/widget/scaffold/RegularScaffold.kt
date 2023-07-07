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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

/**
 * A [Scaffold] which content is scrollable and wrapped in a [Column].
 *
 * For example, this is for the pages with some preferences and is scrollable when the items out of
 * the screen.
 */
@Composable
fun RegularScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    SettingsScaffold(title, actions) { paddingValues ->
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(paddingValues.calculateTopPadding()))
            content()
            Spacer(Modifier.height(paddingValues.calculateBottomPadding()))
        }
    }
}

@Preview
@Composable
private fun RegularScaffoldPreview() {
    SettingsTheme {
        RegularScaffold(title = "Display") {
            Preference(object : PreferenceModel {
                override val title = "Item 1"
            })
            Preference(object : PreferenceModel {
                override val title = "Item 2"
            })
        }
    }
}
