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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.settingslib.spa.framework.theme.divider
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.components.YAxis.AxisDependency
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
     * The color list for [BarChart].
     */
    val colors: List<Color>

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

    /** If set to true, touch gestures are enabled on the [BarChart]. */
    val enableBarchartTouch: Boolean
        get() = true

    /** The renderer provider for x-axis. */
    val xAxisRendererProvider: XAxisRendererProvider?
        get() = null
}

data class BarChartData(
    var x: Float,
    var y: List<Float>,
)

@Composable
fun BarChart(barChartModel: BarChartModel) {
    Column(
        modifier = Modifier
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
            Crossfade(
                targetState = barChartModel.chartDataList,
                label = "chartDataList",
            ) { barChartData ->
                AndroidView(
                    factory = { context ->
                        BarChart(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            description.isEnabled = false
                            legend.isEnabled = false
                            extraBottomOffset = 4f
                            setScaleEnabled(false)
                            setTouchEnabled(barChartModel.enableBarchartTouch)

                            xAxis.apply {
                                position = XAxis.XAxisPosition.BOTTOM
                                setDrawAxisLine(false)
                                setDrawGridLines(false)
                                textColor = labelTextColor
                                textSize = labelTextSize
                                valueFormatter = barChartModel.xValueFormatter
                                yOffset = 10f
                            }

                            barChartModel.xAxisRendererProvider?.let {
                                setXAxisRenderer(
                                    it.provideXAxisRenderer(
                                        getViewPortHandler(),
                                        getXAxis(),
                                        getTransformer(YAxis.AxisDependency.LEFT)
                                    )
                                )
                            }

                            axisLeft.apply {
                                axisMaximum = barChartModel.yAxisMaxValue
                                axisMinimum = barChartModel.yAxisMinValue
                                isEnabled = false
                            }

                            axisRight.apply {
                                axisMaximum = barChartModel.yAxisMaxValue
                                axisMinimum = barChartModel.yAxisMinValue
                                gridColor = colorScheme.divider.toArgb()
                                setDrawAxisLine(false)
                                setLabelCount(barChartModel.yAxisLabelCount, true)
                                textColor = labelTextColor
                                textSize = labelTextSize
                                valueFormatter = barChartModel.yValueFormatter
                                xOffset = 10f
                            }
                        }
                    },
                    modifier = Modifier
                        .wrapContentSize()
                        .padding(4.dp),
                    update = { barChart ->
                        updateBarChartWithData(
                            chart = barChart,
                            data = barChartData,
                            colorList = barChartModel.colors,
                            colorScheme = colorScheme,
                        )
                    }
                )
            }
        }
    }
}

private fun updateBarChartWithData(
    chart: BarChart,
    data: List<BarChartData>,
    colorList: List<Color>,
    colorScheme: ColorScheme
) {
    val entries = data.map { item ->
        BarEntry(item.x, item.y.toFloatArray())
    }

    val ds = BarDataSet(entries, "").apply {
        colors = colorList.map(Color::toArgb)
        setDrawValues(false)
        isHighlightEnabled = true
        highLightColor = colorScheme.primary.toArgb()
        highLightAlpha = 255
    }
    // TODO: Sets round corners for bars.

    chart.data = BarData(ds)
    chart.invalidate()
}
