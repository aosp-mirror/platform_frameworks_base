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

package com.android.settingslib.spa.gallery.chart

import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.widget.chart.BarChart
import com.android.settingslib.spa.widget.chart.BarChartData
import com.android.settingslib.spa.widget.chart.BarChartModel
import com.android.settingslib.spa.widget.chart.ColorPalette
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.github.mikephil.charting.formatter.IAxisValueFormatter

fun createBarChartEntry(owner: SettingsPage) = SettingsEntryBuilder.create("Bar Chart", owner)
    .setUiLayoutFn {
        Preference(object : PreferenceModel {
            override val title = "Bar Chart"
        })
        BarChart(
            barChartModel = object : BarChartModel {
                override val chartDataList = listOf(
                    BarChartData(x = 0f, y = listOf(12f, 2f)),
                    BarChartData(x = 1f, y = listOf(5f, 1f)),
                    BarChartData(x = 2f, y = listOf(21f, 2f)),
                    BarChartData(x = 3f, y = listOf(5f, 1f)),
                    BarChartData(x = 4f, y = listOf(10f, 0f)),
                    BarChartData(x = 5f, y = listOf(9f, 1f)),
                    BarChartData(x = 6f, y = listOf(1f, 1f)),
                )
                override val colors = listOf(ColorPalette.green, ColorPalette.yellow)
                override val xValueFormatter = IAxisValueFormatter { value, _ ->
                    "4/${value.toInt() + 1}"
                }
                override val yValueFormatter = IAxisValueFormatter { value, _ ->
                    "${value.toInt()}m"
                }
                override val yAxisMaxValue = 30f
            }
        )
    }.build()
