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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry

/**
 * The chart settings model for [PieChart].
 */
interface PieChartModel {
    /**
     * The chart data list for [PieChart].
     */
    val chartDataList: List<PieChartData>

    /**
     * The center text in the hole of [PieChart].
     */
    val centerText: String?
        get() = null
}

val colorPalette = arrayListOf(
    ColorPalette.blue.toArgb(),
    ColorPalette.red.toArgb(),
    ColorPalette.yellow.toArgb(),
    ColorPalette.green.toArgb(),
    ColorPalette.orange.toArgb(),
    ColorPalette.cyan.toArgb(),
    Color.Blue.toArgb()
)

data class PieChartData(
    var value: Float?,
    var label: String?,
)

@Composable
fun PieChart(pieChartModel: PieChartModel) {
    PieChart(
        chartDataList = pieChartModel.chartDataList,
        centerText = pieChartModel.centerText,
    )
}

@Composable
fun PieChart(
    chartDataList: List<PieChartData>,
    modifier: Modifier = Modifier,
    centerText: String? = null,
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
                .height(280.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val colorScheme = MaterialTheme.colorScheme
            val labelTextColor = colorScheme.onSurfaceVariant.toArgb()
            val labelTextSize = MaterialTheme.typography.bodyMedium.fontSize.value
            val centerTextSize = MaterialTheme.typography.titleLarge.fontSize.value
            Crossfade(targetState = chartDataList) { pieChartData ->
                AndroidView(factory = { context ->
                    PieChart(context).apply {
                        // Fixed settings.`
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        this.isRotationEnabled = false
                        this.description.isEnabled = false
                        this.legend.isEnabled = false
                        this.setTouchEnabled(false)

                        this.isDrawHoleEnabled = true
                        this.holeRadius = 90.0f
                        this.setHoleColor(Color.Transparent.toArgb())
                        this.setEntryLabelColor(labelTextColor)
                        this.setEntryLabelTextSize(labelTextSize)
                        this.setCenterTextSize(centerTextSize)
                        this.setCenterTextColor(colorScheme.onSurface.toArgb())

                        // Customizable settings.
                        this.centerText = centerText
                    }
                },
                    modifier = Modifier
                        .wrapContentSize()
                        .padding(4.dp), update = {
                        updatePieChartWithData(it, pieChartData)
                    })
            }
        }
    }
}

fun updatePieChartWithData(
    chart: PieChart,
    data: List<PieChartData>,
) {
    val entries = ArrayList<PieEntry>()
    for (i in data.indices) {
        val item = data[i]
        entries.add(PieEntry(item.value ?: 0.toFloat(), item.label ?: ""))
    }

    val ds = PieDataSet(entries, "")
    ds.setDrawValues(false)
    ds.colors = colorPalette
    ds.sliceSpace = 2f
    ds.yValuePosition = PieDataSet.ValuePosition.OUTSIDE_SLICE
    ds.xValuePosition = PieDataSet.ValuePosition.OUTSIDE_SLICE
    ds.valueLineColor = Color.Transparent.toArgb()
    ds.valueLinePart1Length = 0.1f
    ds.valueLinePart2Length = 0f

    val d = PieData(ds)
    chart.data = d
    chart.invalidate()
}
