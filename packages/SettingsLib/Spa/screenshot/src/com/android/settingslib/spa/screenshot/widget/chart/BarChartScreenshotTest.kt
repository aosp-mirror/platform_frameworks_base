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

package com.android.settingslib.spa.screenshot.widget.chart

import androidx.compose.material3.MaterialTheme
import com.android.settingslib.spa.screenshot.util.SettingsScreenshotTestRule
import com.android.settingslib.spa.widget.chart.BarChart
import com.android.settingslib.spa.widget.chart.BarChartData
import com.android.settingslib.spa.widget.chart.BarChartModel
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters
import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.PhoneAndTabletMinimal

/** A screenshot test for ExampleFeature. */
@RunWith(ParameterizedAndroidJunit4::class)
class BarChartScreenshotTest(emulationSpec: DeviceEmulationSpec) {
    companion object {
        @Parameters(name = "{0}")
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
            val color = MaterialTheme.colorScheme.surfaceVariant
            BarChart(
                barChartModel = object : BarChartModel {
                    override val chartDataList = listOf(
                        BarChartData(x = 0f, y = listOf(12f)),
                        BarChartData(x = 1f, y = listOf(5f)),
                        BarChartData(x = 2f, y = listOf(21f)),
                        BarChartData(x = 3f, y = listOf(5f)),
                        BarChartData(x = 4f, y = listOf(10f)),
                        BarChartData(x = 5f, y = listOf(9f)),
                        BarChartData(x = 6f, y = listOf(1f)),
                    )
                    override val colors = listOf(color)
                    override val xValueFormatter =
                        IAxisValueFormatter { value, _ ->
                            "${WeekDay.entries[value.toInt()]}"
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
