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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessAlarm
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.SliderPreference
import com.android.settingslib.spa.widget.preference.SliderPreferenceModel
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel

private const val TITLE = "Sample Slider"

object SliderPageProvider : SettingsPageProvider {
    override val name = "Slider"
    private val owner = createSettingsPage()

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            SettingsEntryBuilder.create("Simple Slider", owner)
                .setUiLayoutFn {
                    SliderPreference(object : SliderPreferenceModel {
                        override val title = "Simple Slider"
                        override val initValue = 40
                    })
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create("Slider with icon", owner)
                .setUiLayoutFn {
                    SliderPreference(object : SliderPreferenceModel {
                        override val title = "Slider with icon"
                        override val initValue = 30
                        override val onValueChangeFinished = {
                            println("onValueChangeFinished")
                        }
                        override val icon = Icons.Outlined.AccessAlarm
                    })
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create("Slider with changeable icon", owner)
                .setUiLayoutFn {
                    val initValue = 0
                    var icon by remember { mutableStateOf(Icons.Outlined.MusicOff) }
                    var sliderPosition by remember { mutableStateOf(initValue) }
                    SliderPreference(object : SliderPreferenceModel {
                        override val title = "Slider with changeable icon"
                        override val initValue = initValue
                        override val onValueChange = { it: Int ->
                            sliderPosition = it
                            icon = if (it > 0) Icons.Outlined.MusicNote else Icons.Outlined.MusicOff
                        }
                        override val onValueChangeFinished = {
                            println("onValueChangeFinished: the value is $sliderPosition")
                        }
                        override val icon = icon
                    })
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create("Slider with steps", owner)
                .setUiLayoutFn {
                    SliderPreference(object : SliderPreferenceModel {
                        override val title = "Slider with steps"
                        override val initValue = 2
                        override val valueRange = 1..5
                        override val showSteps = true
                    })
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

@Preview(showBackground = true)
@Composable
private fun SliderPagePreview() {
    SettingsTheme {
        SliderPageProvider.Page(null)
    }
}
