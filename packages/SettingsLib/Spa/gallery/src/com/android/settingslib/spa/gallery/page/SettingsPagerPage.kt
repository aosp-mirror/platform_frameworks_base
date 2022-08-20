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

package com.android.settingslib.spa.gallery.page

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.api.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.SettingsPager
import com.android.settingslib.spa.widget.ui.SettingsTitle

object SettingsPagerPageProvider : SettingsPageProvider {
    override val name = "SettingsPager"

    @Composable
    override fun Page(arguments: Bundle?) {
        SettingsPagerPage()
    }

    @Composable
    fun EntryItem() {
        Preference(object : PreferenceModel {
            override val title = "Sample SettingsPager"
            override val onClick = navigator(name)
        })
    }
}

@Composable
private fun SettingsPagerPage() {
    SettingsPager(listOf("Personal", "Work")) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SettingsTitle(title = "Page $it")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPagerPagePreview() {
    SettingsTheme {
        SettingsPagerPage()
    }
}
