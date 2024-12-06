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

package com.android.settingslib.spa.gallery.preference

import android.os.Bundle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.TwoTargetButtonPreference
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category

private const val TITLE = "Sample TwoTargetButtonPreference"

object TwoTargetButtonPreferencePageProvider : SettingsPageProvider {
    override val name = "TwoTargetButtonPreference"

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(TITLE) {
            Category {
                SampleTwoTargetButtonPreference()
                SampleTwoTargetButtonPreferenceWithSummary()
            }
        }
    }

    @Composable
    fun Entry() {
        Preference(
            object : PreferenceModel {
                override val title = TITLE
                override val onClick = navigator(name)
            }
        )
    }
}

@Composable
private fun SampleTwoTargetButtonPreference() {
    TwoTargetButtonPreference(
        title = "TwoTargetButton",
        summary = { "" },
        buttonIcon = Icons.Outlined.Info,
        buttonIconDescription = "info",
        onClick = {},
        onButtonClick = {},
    )
}

@Composable
private fun SampleTwoTargetButtonPreferenceWithSummary() {
    TwoTargetButtonPreference(
        title = "TwoTargetButton",
        summary = { "summary" },
        buttonIcon = Icons.Outlined.Add,
        buttonIconDescription = "info",
        onClick = {},
        onButtonClick = {},
    )
}
