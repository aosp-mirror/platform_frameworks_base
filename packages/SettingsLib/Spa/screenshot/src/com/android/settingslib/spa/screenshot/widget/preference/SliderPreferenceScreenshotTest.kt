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

package com.android.settingslib.spa.screenshot.widget.preference

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessAlarm
import com.android.settingslib.spa.screenshot.util.settingsScreenshotTestRule
import com.android.settingslib.spa.widget.preference.SliderPreference
import com.android.settingslib.spa.widget.preference.SliderPreferenceModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters
import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.PhoneAndTabletMinimal

/** A screenshot test for ExampleFeature. */
@RunWith(ParameterizedAndroidJunit4::class)
class SliderPreferenceScreenshotTest(emulationSpec: DeviceEmulationSpec) {
    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun getTestSpecs() = DeviceEmulationSpec.PhoneAndTabletMinimal
    }

    @get:Rule
    val screenshotRule =
        settingsScreenshotTestRule(
            emulationSpec,
        )

    @Test
    fun test() {
        screenshotRule.screenshotTest("slider") {
            Column {
                SliderPreference(object : SliderPreferenceModel {
                    override val title = "Simple Slider"
                    override val initValue = 40
                })

                SliderPreference(object : SliderPreferenceModel {
                    override val title = "Slider with icon"
                    override val initValue = 30
                    override val onValueChangeFinished = {
                        println("onValueChangeFinished")
                    }
                    override val icon = Icons.Outlined.AccessAlarm
                })

                SliderPreference(object : SliderPreferenceModel {
                    override val title = "Slider with steps"
                    override val initValue = 2
                    override val valueRange = 1..5
                    override val showSteps = true
                })
            }
        }
    }
}
