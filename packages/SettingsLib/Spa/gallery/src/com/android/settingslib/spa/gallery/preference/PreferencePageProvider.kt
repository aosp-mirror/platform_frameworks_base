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
import androidx.compose.material.icons.outlined.DisabledByDefault
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.gallery.R
import com.android.settingslib.spa.widget.preference.ListPreferenceModel
import com.android.settingslib.spa.widget.preference.ListPreferenceOption
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.RadioPreferences
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.SettingsIcon
import kotlinx.coroutines.delay

object PreferencePageProvider : SettingsPageProvider {

    override val name = "Preference"
    private const val PAGE_TITLE = "Sample Preference"

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(PAGE_TITLE) {
            Category {
                Preference(object : PreferenceModel {
                    override val title = "Preference"
                })
                Preference(object : PreferenceModel {
                    override val title = "Preference"
                    override val summary = { "Simple summary" }
                })
                val summary = stringResource(R.string.single_line_summary_preference_summary)
                Preference(
                    model = object : PreferenceModel {
                        override val title =
                            stringResource(R.string.single_line_summary_preference_title)
                        override val summary = { summary }
                    },
                    singleLineSummary = true,
                )
            }
            Category {
                Preference(object : PreferenceModel {
                    override val title = "Disabled"
                    override val summary = { "Disabled summary" }
                    override val enabled = { false }
                    override val icon = @Composable {
                        SettingsIcon(imageVector = Icons.Outlined.DisabledByDefault)
                    }
                })
            }
            Category {
                Preference(object : PreferenceModel {
                    override val title = "Preference"
                    val asyncSummary by produceState(initialValue = " ") {
                        delay(1000L)
                        value = "Async summary"
                    }
                    override val summary = { asyncSummary }
                })

                var count by remember { mutableIntStateOf(0) }
                Preference(object : PreferenceModel {
                    override val title = "Click me"
                    override val summary = { count.toString() }
                    override val onClick: (() -> Unit) = { count++ }
                })

                var ticks by remember { mutableIntStateOf(0) }
                LaunchedEffect(ticks) {
                    delay(1000L)
                    ticks++
                }
                Preference(object : PreferenceModel {
                    override val title = "Ticker"
                    override val summary = { ticks.toString() }
                })
            }
            val selectedId = rememberSaveable { mutableIntStateOf(0) }
            RadioPreferences(
                object : ListPreferenceModel {
                    override val title: String = "RadioPreferences"
                    override val options: List<ListPreferenceOption> =
                        listOf(
                            ListPreferenceOption(id = 0, text = "option1"),
                            ListPreferenceOption(id = 1, text = "option2"),
                            ListPreferenceOption(id = 2, text = "option3"),
                        )
                    override val selectedId: IntState = selectedId
                    override val onIdSelected: (Int) -> Unit = {
                        selectedId.intValue = it
                    }
                }
            )
        }
    }

    @Composable
    fun Entry() {
        Preference(model = object : PreferenceModel {
            override val title = PAGE_TITLE
            override val onClick = navigator(name)
        })
    }
}

@Preview(showBackground = true)
@Composable
private fun PreferencePagePreview() {
    SpaEnvironmentFactory.resetForPreview()
    SettingsTheme {
        PreferencePageProvider.Page(null)
    }
}
