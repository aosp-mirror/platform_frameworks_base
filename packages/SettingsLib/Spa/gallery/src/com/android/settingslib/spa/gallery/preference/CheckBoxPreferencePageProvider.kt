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

package com.android.settingslib.spa.gallery.preference

import android.os.Bundle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.widget.preference.CheckboxPreference
import com.android.settingslib.spa.widget.preference.CheckboxPreferenceModel
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.SettingsIcon

private const val TITLE = "Sample CheckBoxPreference"

object CheckBoxPreferencePageProvider : SettingsPageProvider {
    override val name = "CheckBoxPreference"

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(TITLE) {
            Category {
                var checked1 by rememberSaveable { mutableStateOf(true) }
                CheckboxPreference(
                    object : CheckboxPreferenceModel {
                        override val title = "Use Dark theme"
                        override val checked = { checked1 }
                        override val onCheckedChange = { newChecked: Boolean ->
                            checked1 = newChecked
                        }
                    }
                )
                var checked2 by rememberSaveable { mutableStateOf(false) }
                CheckboxPreference(
                    object : CheckboxPreferenceModel {
                        override val title = "Use Dark theme"
                        override val summary = { "Summary" }
                        override val checked = { checked2 }
                        override val onCheckedChange = { newChecked: Boolean ->
                            checked2 = newChecked
                        }
                    }
                )
                var checked3 by rememberSaveable { mutableStateOf(true) }
                CheckboxPreference(
                    object : CheckboxPreferenceModel {
                        override val title = "Use Dark theme"
                        override val summary = { "Summary" }
                        override val checked = { checked3 }
                        override val onCheckedChange = { newChecked: Boolean ->
                            checked3 = newChecked
                        }
                        override val icon =
                            @Composable {
                                SettingsIcon(imageVector = Icons.Outlined.AirplanemodeActive)
                            }
                    }
                )
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

    override fun getTitle(arguments: Bundle?): String {
        return TITLE
    }
}
