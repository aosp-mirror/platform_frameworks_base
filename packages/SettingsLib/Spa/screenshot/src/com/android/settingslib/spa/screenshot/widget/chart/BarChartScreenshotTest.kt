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

import com.android.settingslib.spa.widget.chart.BarChart
import com.android.settingslib.spa.widget.chart.BarChartData
import com.android.settingslib.spa.widget.chart.BarChartModel
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import platform.test.screenshot.DeviceEmulationSpec

/** A screenshot test for ExampleFeature. */
@RunWith(Parameterized::class)
class BarChartScreenshotTest(emulationSpec: DeviceEmulationSpec) {
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
        screenshotRule.screenshotTest("barChart") {
            BarChart(
                barChartModel = object : BarChartModel {
                    override val chartDataList = listOf(
                        BarChartData(x = 0f, y = 12f),
                        BarChartData(x = 1f, y = 5f),
                        BarChartData(x = 2f, y = 21f),
                        BarChartData(x = 3f, y = 5f),
                        BarChartData(x = 4f, y = 10f),
                        BarChartData(x = 5f, y = 9f),
                        BarChartData(x = 6f, y = 1f),
                    )
                    override val xValueFormatter =
                        IAxisValueFormatter { value, _ ->
                            "${WeekDay.values()[value.toInt()]}"
                        }
                    override val yValueFormatter =
                        IAxisValueFormatter { value, _ ->
                            "${value.toInt()}m"
                        }
                    override val yAxisMaxValue = 30f
                }
            )
        }
    }

    private enum class WeekDay(val num: Int) {
        Sun(0), Mon(1), Tue(2), Wed(3), Thu(4), Fri(5), Sat(6),
    }
}
