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

package com.android.settingslib.spa.gallery.preference

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import kotlinx.coroutines.delay

private const val TITLE = "Sample SwitchPreference"

object SwitchPreferencePageProvider : SettingsPageProvider {
    override val name = "SwitchPreference"

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = SettingsPage.create(name)
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            SettingsEntryBuilder.create( "SwitchPreference", owner)
                .setIsAllowSearch(true)
                .setUiLayoutFn {
                    SampleSwitchPreference()
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create( "SwitchPreference with summary", owner)
                .setIsAllowSearch(true)
                .setUiLayoutFn {
                    SampleSwitchPreferenceWithSummary()
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create( "SwitchPreference with async summary", owner)
                .setIsAllowSearch(true)
                .setUiLayoutFn {
                    SampleSwitchPreferenceWithAsyncSummary()
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create( "SwitchPreference not changeable", owner)
                .setIsAllowSearch(true)
                .setUiLayoutFn {
                    SampleNotChangeableSwitchPreference()
                }.build()
        )

        return entryList
    }

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner = SettingsPage.create(name))
            .setIsAllowSearch(true)
            .setUiLayoutFn {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val onClick = navigator(name)
                })
            }
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        SwitchPreferencePage()
    }

    @Composable
    fun EntryItem() {
        buildInjectEntry().build().uiLayout.let { it() }
    }
}

@Composable
private fun SwitchPreferencePage() {
    RegularScaffold(title = TITLE) {
        for (entry in SwitchPreferencePageProvider.buildEntry(arguments = null)) {
            entry.uiLayout()
        }
    }
}

@Composable
private fun SampleSwitchPreference() {
    val checked = rememberSaveable { mutableStateOf(false) }
    SwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "SwitchPreference"
            override val checked = checked
            override val onCheckedChange = { newChecked: Boolean -> checked.value = newChecked }
        }
    })
}

@Composable
private fun SampleSwitchPreferenceWithSummary() {
    val checked = rememberSaveable { mutableStateOf(true) }
    SwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "SwitchPreference"
            override val summary = stateOf("With summary")
            override val checked = checked
            override val onCheckedChange = { newChecked: Boolean -> checked.value = newChecked }
        }
    })
}

@Composable
private fun SampleSwitchPreferenceWithAsyncSummary() {
    val checked = rememberSaveable { mutableStateOf(true) }
    val summary = produceState(initialValue = " ") {
        delay(1000L)
        value = "Async summary"
    }
    SwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "SwitchPreference"
            override val summary = summary
            override val checked = checked
            override val onCheckedChange = { newChecked: Boolean -> checked.value = newChecked }
        }
    })
}

@Composable
private fun SampleNotChangeableSwitchPreference() {
    val checked = rememberSaveable { mutableStateOf(true) }
    SwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "SwitchPreference"
            override val summary = stateOf("Not changeable")
            override val changeable = stateOf(false)
            override val checked = checked
            override val onCheckedChange = { newChecked: Boolean -> checked.value = newChecked }
        }
    })
}

@Preview(showBackground = true)
@Composable
private fun SwitchPreferencePagePreview() {
    SettingsTheme {
        SwitchPreferencePage()
    }
}
