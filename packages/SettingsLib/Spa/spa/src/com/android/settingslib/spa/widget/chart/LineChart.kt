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
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IAxisValueFormatter

/**
 * The chart settings model for [LineChart].
 */
interface LineChartModel {
    /**
     * The chart data list for [LineChart].
     */
    val chartDataList: List<LineChartData>

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

    /**
     * Indicates whether to smooth the line.
     */
    val showSmoothLine: Boolean
        get() = true
}

data class LineChartData(
    var x: Float?,
    var y: Float?,
)

@Composable
fun LineChart(lineChartModel: LineChartModel) {
    LineChart(
        chartDataList = lineChartModel.chartDataList,
        xValueFormatter = lineChartModel.xValueFormatter,
        yValueFormatter = lineChartModel.yValueFormatter,
        yAxisMinValue = lineChartModel.yAxisMinValue,
        yAxisMaxValue = lineChartModel.yAxisMaxValue,
        yAxisLabelCount = lineChartModel.yAxisLabelCount,
        showSmoothLine = lineChartModel.showSmoothLine,
    )
}

@Composable
fun LineChart(
    chartDataList: List<LineChartData>,
    modifier: Modifier = Modifier,
    xValueFormatter: IAxisValueFormatter? = null,
    yValueFormatter: IAxisValueFormatter? = null,
    yAxisMinValue: Float = 0f,
    yAxisMaxValue: Float = 1f,
    yAxisLabelCount: Int = 3,
    showSmoothLine: Boolean = true,
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
            Crossfade(targetState = chartDataList) { lineChartData ->
                AndroidView(factory = { context ->
                    LineChart(context).apply {
                        // Fixed Settings.
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this.description.isEnabled = false
                        this.legend.isEnabled = false
                        this.extraBottomOffset = 4f
                        this.setTouchEnabled(false)

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
                        this.axisRight.isGranularityEnabled = true

                        // Customizable Settings.
                        this.xAxis.valueFormatter = xValueFormatter
                        this.axisRight.valueFormatter = yValueFormatter

                        this.axisLeft.axisMinimum =
                            yAxisMinValue - 0.01f * (yAxisMaxValue - yAxisMinValue)
                        this.axisRight.axisMinimum =
                            yAxisMinValue - 0.01f * (yAxisMaxValue - yAxisMinValue)
                        this.axisRight.granularity =
                            (yAxisMaxValue - yAxisMinValue) / (yAxisLabelCount - 1)
                    }
                },
                    modifier = Modifier
                        .wrapContentSize()
                        .padding(4.dp),
                    update = {
                        updateLineChartWithData(it, lineChartData, colorScheme, showSmoothLine)
                    })
            }
        }
    }
}

fun updateLineChartWithData(
    chart: LineChart,
    data: List<LineChartData>,
    colorScheme: ColorScheme,
    showSmoothLine: Boolean
) {
    val entries = ArrayList<Entry>()
    for (i in data.indices) {
        val item = data[i]
        entries.add(Entry(item.x ?: 0.toFloat(), item.y ?: 0.toFloat()))
    }

    val ds = LineDataSet(entries, "")
    ds.colors = arrayListOf(colorScheme.primary.toArgb())
    ds.lineWidth = 2f
    if (showSmoothLine) {
        ds.mode = LineDataSet.Mode.CUBIC_BEZIER
    }
    ds.setDrawValues(false)
    ds.setDrawCircles(false)
    ds.setDrawFilled(true)
    ds.fillColor = colorScheme.primary.toArgb()
    ds.fillAlpha = 38
    // TODO: enable gradient fill color for line chart.

    val d = LineData(ds)
    chart.data = d
    chart.invalidate()
}
