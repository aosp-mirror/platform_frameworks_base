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

package com.android.settingslib.spa.gallery.scaffold

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.SettingsPager
import com.android.settingslib.spa.widget.scaffold.SettingsScaffold

object ScrollablePagerPageProvider : SettingsPageProvider {
    override val name = "ScrollablePager"
    private const val TITLE = "Sample Scrollable SettingsPager"

    fun buildInjectEntry() = SettingsEntryBuilder.createInject(owner = createSettingsPage())
        .setUiLayoutFn {
            Preference(object : PreferenceModel {
                override val title = TITLE
                override val onClick = navigator(name)
            })
        }

    override fun getTitle(arguments: Bundle?) = TITLE

    @Composable
    override fun Page(arguments: Bundle?) {
        SettingsScaffold(title = getTitle(arguments)) { paddingValues ->
            Box(Modifier.padding(paddingValues)) {
                SettingsPager(listOf("Personal", "Work")) {
                    LazyColumn {
                        items(30) {
                            Preference(object : PreferenceModel {
                                override val title = it.toString()
                            })
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ScrollablePagerPageProviderPreview() {
    SettingsTheme {
        ScrollablePagerPageProvider.Page(null)
    }
}
