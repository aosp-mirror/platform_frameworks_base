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

import com.android.settingslib.spa.screenshot.util.settingsScreenshotTestRule
import com.android.settingslib.spa.widget.chart.LineChart
import com.android.settingslib.spa.widget.chart.LineChartData
import com.android.settingslib.spa.widget.chart.LineChartModel
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import java.text.NumberFormat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters
import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.PhoneAndTabletMinimal

/** A screenshot test for ExampleFeature. */
@RunWith(ParameterizedAndroidJunit4::class)
class LineChartScreenshotTest(emulationSpec: DeviceEmulationSpec) {
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
        screenshotRule.screenshotTest("lineChart") {
            LineChart(
                lineChartModel = object : LineChartModel {
                    override val chartDataList = listOf(
                        LineChartData(x = 0f, y = 0f),
                        LineChartData(x = 1f, y = 0.1f),
                        LineChartData(x = 2f, y = 0.2f),
                        LineChartData(x = 3f, y = 0.6f),
                        LineChartData(x = 4f, y = 0.9f),
                        LineChartData(x = 5f, y = 1.0f),
                        LineChartData(x = 6f, y = 0.8f),
                    )
                    override val xValueFormatter =
                        IAxisValueFormatter { value, _ ->
                            "${WeekDay.entries[value.toInt()]}"
                        }
                    override val yValueFormatter =
                        IAxisValueFormatter { value, _ ->
                            NumberFormat.getPercentInstance().format(value)
                        }
                }
            )
        }
    }

    private enum class WeekDay(val num: Int) {
        Sun(0), Mon(1), Tue(2), Wed(3), Thu(4), Fri(5), Sat(6),
    }
}
