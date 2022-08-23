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
import com.android.settingslib.spa.framework.api.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.SettingsSlider
import com.android.settingslib.spa.widget.ui.SettingsSliderModel

private const val TITLE = "Sample Slider"

object SliderPageProvider : SettingsPageProvider {
    override val name = "Slider"

    @Composable
    override fun Page(arguments: Bundle?) {
        SliderPage()
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
private fun SliderPage() {
    RegularScaffold(title = TITLE) {
        SettingsSlider(object : SettingsSliderModel {
            override val title = "Slider"
            override val initValue = 40
        })

        SettingsSlider(object : SettingsSliderModel {
            override val title = "Slider with icon"
            override val initValue = 30
            override val onValueChangeFinished = {
                println("onValueChangeFinished")
            }
            override val icon = Icons.Outlined.AccessAlarm
        })

        val initValue = 0
        var icon by remember { mutableStateOf(Icons.Outlined.MusicOff) }
        var sliderPosition by remember { mutableStateOf(initValue) }
        SettingsSlider(object : SettingsSliderModel {
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

        SettingsSlider(object : SettingsSliderModel {
            override val title = "Slider with steps"
            override val initValue = 2
            override val valueRange = 1..5
            override val showSteps = true
        })
    }
}

@Preview(showBackground = true)
@Composable
private fun SliderPagePreview() {
    SettingsTheme {
        SliderPage()
    }
}
