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

package com.android.settingslib.spa.widget.chart

import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.settingslib.spa.framework.theme.divider
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IAxisValueFormatter

/**
 * The chart settings model for [BarChart].
 */
interface BarChartModel {
    /**
     * The chart data list for [BarChart].
     */
    val chartDataList: List<BarChartData>

    /**
     * The label text formatter for x value.
     */
    val xValueFormatter: IAxisValueFormatter?
        get() = null

    /**
     * The label text formatter for y value.
     */
    val yValueFormatter: IAxisValueFormatter?
        get() = null

    /**
     * The minimum value for y-axis.
     */
    val yAxisMinValue: Float
        get() = 0f

    /**
     * The maximum value for y-axis.
     */
    val yAxisMaxValue: Float
        get() = 1f

    /**
     * The label count for y-axis.
     */
    val yAxisLabelCount: Int
        get() = 3
}

data class BarChartData(
    var x: Float?,
    var y: Float?,
)

@Composable
fun BarChart(barChartModel: BarChartModel) {
    BarChart(
        chartDataList = barChartModel.chartDataList,
        xValueFormatter = barChartModel.xValueFormatter,
        yValueFormatter = barChartModel.yValueFormatter,
        yAxisMinValue = barChartModel.yAxisMinValue,
        yAxisMaxValue = barChartModel.yAxisMaxValue,
        yAxisLabelCount = barChartModel.yAxisLabelCount,
    )
}

@Composable
fun BarChart(
    chartDataList: List<BarChartData>,
    modifier: Modifier = Modifier,
    xValueFormatter: IAxisValueFormatter? = null,
    yValueFormatter: IAxisValueFormatter? = null,
    yAxisMinValue: Float = 0f,
    yAxisMaxValue: Float = 30f,
    yAxisLabelCount: Int = 4,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .height(170.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val colorScheme = MaterialTheme.colorScheme
            val labelTextColor = colorScheme.onSurfaceVariant.toArgb()
            val labelTextSize = MaterialTheme.typography.bodyMedium.fontSize.value
            Crossfade(targetState = chartDataList) { barChartData ->
                AndroidView(factory = { context ->
                    BarChart(context).apply {
                        // Fixed Settings.
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this.description.isEnabled = false
                        this.legend.isEnabled = false
                        this.extraBottomOffset = 4f
                        this.setScaleEnabled(false)

                        this.xAxis.position = XAxis.XAxisPosition.BOTTOM
                        this.xAxis.setDrawGridLines(false)
                        this.xAxis.setDrawAxisLine(false)
                        this.xAxis.textColor = labelTextColor
                        this.xAxis.textSize = labelTextSize
                        this.xAxis.yOffset = 10f

                        this.axisLeft.isEnabled = false
                        this.axisRight.setDrawAxisLine(false)
                        this.axisRight.textSize = labelTextSize
                        this.axisRight.textColor = labelTextColor
                        this.axisRight.gridColor = colorScheme.divider.toArgb()
                        this.axisRight.xOffset = 10f

                        // Customizable Settings.
                        this.xAxis.valueFormatter = xValueFormatter
                        this.axisRight.valueFormatter = yValueFormatter

                        this.axisLeft.axisMinimum = yAxisMinValue
                        this.axisLeft.axisMaximum = yAxisMaxValue
                        this.axisRight.axisMinimum = yAxisMinValue
                        this.axisRight.axisMaximum = yAxisMaxValue

                        this.axisRight.setLabelCount(yAxisLabelCount, true)
                    }
                },
                    modifier = Modifier
                        .wrapContentSize()
                        .padding(4.dp),
                    update = {
                        updateBarChartWithData(it, barChartData, colorScheme)
                    }
                )
            }
        }
    }
}

fun updateBarChartWithData(
    chart: BarChart,
    data: List<BarChartData>,
    colorScheme: ColorScheme
) {
    val entries = ArrayList<BarEntry>()
    for (i in data.indices) {
        val item = data[i]
        entries.add(BarEntry(item.x ?: 0.toFloat(), item.y ?: 0.toFloat()))
    }

    val ds = BarDataSet(entries, "")
    ds.colors = arrayListOf(colorScheme.surfaceVariant.toArgb())
    ds.setDrawValues(false)
    ds.isHighlightEnabled = true
    ds.highLightColor = colorScheme.primary.toArgb()
    ds.highLightAlpha = 255
    // TODO: Sets round corners for bars.

    val d = BarData(ds)
    chart.data = d
    chart.invalidate()
}
