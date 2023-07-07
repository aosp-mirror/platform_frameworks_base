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

package com.android.settingslib.spa.screenshot

import com.android.settingslib.spa.widget.chart.PieChart
import com.android.settingslib.spa.widget.chart.PieChartData
import com.android.settingslib.spa.widget.chart.PieChartModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import platform.test.screenshot.DeviceEmulationSpec

/** A screenshot test for ExampleFeature. */
@RunWith(Parameterized::class)
class PieChartScreenshotTest(emulationSpec: DeviceEmulationSpec) {
    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getTestSpecs() = DeviceEmulationSpec.PhoneAndTabletMinimal
    }

    @get:Rule
    val screenshotRule =
        SettingsScreenshotTestRule(
            emulationSpec,
            "frameworks/base/packages/SettingsLib/Spa/screenshot/assets"
        )

    @Test
    fun test() {
        screenshotRule.screenshotTest("pieChart") {
            PieChart(
                pieChartModel = object : PieChartModel {
                    override val chartDataList = listOf(
                        PieChartData(label = "Settings", value = 20f),
                        PieChartData(label = "Chrome", value = 5f),
                        PieChartData(label = "Gmail", value = 3f),
                    )
                    override val centerText = "Today"
                }
            )
        }
    }
}