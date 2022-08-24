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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.api.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Footer

private const val TITLE = "Sample Footer"

object FooterPageProvider : SettingsPageProvider {
    override val name = "Footer"

    @Composable
    override fun Page(arguments: Bundle?) {
        FooterPage()
    }

    @Composable
    fun EntryItem() {
        Preference(object : PreferenceModel {
            override val title = TITLE
            override val onClick = navigator(name)
        })
    }
}

@Composable
private fun FooterPage() {
    RegularScaffold(title = TITLE) {
        Preference(remember {
            object : PreferenceModel {
                override val title = "Some Preference"
                override val summary = stateOf("Some summary")
            }
        })
        Footer(footerText = "Footer text always at the end of page.")
    }
}

@Preview(showBackground = true)
@Composable
private fun FooterPagePreview() {
    SettingsTheme {
        FooterPage()
    }
}
