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
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.ui.SettingsIcon
import kotlinx.coroutines.delay

private const val TITLE = "Sample SwitchPreference"

object SwitchPreferencePageProvider : SettingsPageProvider {
    override val name = "SwitchPreference"
    private val owner = createSettingsPage()

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            SettingsEntryBuilder.create( "SwitchPreference", owner)
                .setUiLayoutFn {
                    SampleSwitchPreference()
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create( "SwitchPreference with summary", owner)
                .setUiLayoutFn {
                    SampleSwitchPreferenceWithSummary()
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create( "SwitchPreference with async summary", owner)
                .setUiLayoutFn {
                    SampleSwitchPreferenceWithAsyncSummary()
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create( "SwitchPreference not changeable", owner)
                .setUiLayoutFn {
                    SampleNotChangeableSwitchPreference()
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create( "SwitchPreference with icon", owner)
                .setUiLayoutFn {
                    SampleSwitchPreferenceWithIcon()
                }.build()
        )

        return entryList
    }

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner)
            .setUiLayoutFn {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val onClick = navigator(name)
                })
            }
    }

    override fun getTitle(arguments: Bundle?): String {
        return TITLE
    }
}

@Composable
private fun SampleSwitchPreference() {
    var checked by rememberSaveable { mutableStateOf(false) }
    SwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "SwitchPreference"
            override val checked = { checked }
            override val onCheckedChange = { newChecked: Boolean -> checked = newChecked }
        }
    })
}

@Composable
private fun SampleSwitchPreferenceWithSummary() {
    var checked by rememberSaveable { mutableStateOf(true) }
    SwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "SwitchPreference"
            override val summary = { "With summary" }
            override val checked = { checked }
            override val onCheckedChange = { newChecked: Boolean -> checked = newChecked }
        }
    })
}

@Composable
private fun SampleSwitchPreferenceWithAsyncSummary() {
    var checked by rememberSaveable { mutableStateOf(true) }
    val summary = produceState(initialValue = " ") {
        delay(1000L)
        value = "Async summary"
    }
    SwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "SwitchPreference"
            override val summary = { summary.value }
            override val checked = { checked }
            override val onCheckedChange = { newChecked: Boolean -> checked = newChecked }
        }
    })
}

@Composable
private fun SampleNotChangeableSwitchPreference() {
    var checked by rememberSaveable { mutableStateOf(true) }
    SwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "SwitchPreference"
            override val summary = { "Not changeable" }
            override val changeable = { false }
            override val checked = { checked }
            override val onCheckedChange = { newChecked: Boolean -> checked = newChecked }
        }
    })
}

@Composable
private fun SampleSwitchPreferenceWithIcon() {
    var checked by rememberSaveable { mutableStateOf(true) }
    SwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "SwitchPreference"
            override val checked = { checked }
            override val onCheckedChange = { newChecked: Boolean -> checked = newChecked }
            override val icon = @Composable {
                SettingsIcon(imageVector = Icons.Outlined.AirplanemodeActive)
            }
        }
    })
}

@Preview(showBackground = true)
@Composable
private fun SwitchPreferencePagePreview() {
    SettingsTheme {
        SwitchPreferencePageProvider.Page(null)
    }
}
