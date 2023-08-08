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

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.chart.LineChart
import com.android.settingslib.spa.widget.chart.LineChartData
import com.android.settingslib.spa.widget.chart.LineChartModel
import com.android.settingslib.spa.widget.chart.PieChart
import com.android.settingslib.spa.widget.chart.PieChartData
import com.android.settingslib.spa.widget.chart.PieChartModel
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import java.text.NumberFormat

private enum class WeekDay(val num: Int) {
    Sun(0), Mon(1), Tue(2), Wed(3), Thu(4), Fri(5), Sat(6),
}
private const val TITLE = "Sample Chart"

object ChartPageProvider : SettingsPageProvider {
    override val name = "Chart"
    private val owner = createSettingsPage()

    override fun getTitle(arguments: Bundle?): String {
        return TITLE
    }

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            SettingsEntryBuilder.create("Line Chart", owner)
                .setUiLayoutFn {
                    Preference(object : PreferenceModel {
                        override val title = "Line Chart"
                    })
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
                                    "${WeekDay.values()[value.toInt()]}"
                                }
                            override val yValueFormatter =
                                IAxisValueFormatter { value, _ ->
                                    NumberFormat.getPercentInstance().format(value)
                                }
                        }
                    )
                }.build()
        )
        entryList.add(createBarChartEntry(owner))
        entryList.add(
            SettingsEntryBuilder.create("Pie Chart", owner)
                .setUiLayoutFn {
                    Preference(object : PreferenceModel {
                        override val title = "Pie Chart"
                    })
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
}

@Preview(showBackground = true)
@Composable
private fun ChartPagePreview() {
    SettingsTheme {
        ChartPageProvider.Page(null)
    }
}
