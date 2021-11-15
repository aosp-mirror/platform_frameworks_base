/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.egg.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews

import com.android.egg.R

/**
 * A homescreen widget to explore the current dynamic system theme.
 */
class PaintChipsWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        // Log.v(TAG, "onAppWidgetOptionsChanged: id=${appWidgetId}")
        updateAppWidget(context, appWidgetManager, appWidgetId)
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    }
}

const val TAG = "PaintChips"

val SHADE_NUMBERS = intArrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

val COLORS_NEUTRAL1 = intArrayOf(
    android.R.color.system_neutral1_0,
    android.R.color.system_neutral1_10,
    android.R.color.system_neutral1_50,
    android.R.color.system_neutral1_100,
    android.R.color.system_neutral1_200,
    android.R.color.system_neutral1_300,
    android.R.color.system_neutral1_400,
    android.R.color.system_neutral1_500,
    android.R.color.system_neutral1_600,
    android.R.color.system_neutral1_700,
    android.R.color.system_neutral1_800,
    android.R.color.system_neutral1_900,
    android.R.color.system_neutral1_1000
)

val COLORS_NEUTRAL2 = intArrayOf(
    android.R.color.system_neutral2_0,
    android.R.color.system_neutral2_10,
    android.R.color.system_neutral2_50,
    android.R.color.system_neutral2_100,
    android.R.color.system_neutral2_200,
    android.R.color.system_neutral2_300,
    android.R.color.system_neutral2_400,
    android.R.color.system_neutral2_500,
    android.R.color.system_neutral2_600,
    android.R.color.system_neutral2_700,
    android.R.color.system_neutral2_800,
    android.R.color.system_neutral2_900,
    android.R.color.system_neutral2_1000
)

var COLORS_ACCENT1 = intArrayOf(
    android.R.color.system_accent1_0,
    android.R.color.system_accent1_10,
    android.R.color.system_accent1_50,
    android.R.color.system_accent1_100,
    android.R.color.system_accent1_200,
    android.R.color.system_accent1_300,
    android.R.color.system_accent1_400,
    android.R.color.system_accent1_500,
    android.R.color.system_accent1_600,
    android.R.color.system_accent1_700,
    android.R.color.system_accent1_800,
    android.R.color.system_accent1_900,
    android.R.color.system_accent1_1000
)

var COLORS_ACCENT2 = intArrayOf(
    android.R.color.system_accent2_0,
    android.R.color.system_accent2_10,
    android.R.color.system_accent2_50,
    android.R.color.system_accent2_100,
    android.R.color.system_accent2_200,
    android.R.color.system_accent2_300,
    android.R.color.system_accent2_400,
    android.R.color.system_accent2_500,
    android.R.color.system_accent2_600,
    android.R.color.system_accent2_700,
    android.R.color.system_accent2_800,
    android.R.color.system_accent2_900,
    android.R.color.system_accent2_1000
)

var COLORS_ACCENT3 = intArrayOf(
    android.R.color.system_accent3_0,
    android.R.color.system_accent3_10,
    android.R.color.system_accent3_50,
    android.R.color.system_accent3_100,
    android.R.color.system_accent3_200,
    android.R.color.system_accent3_300,
    android.R.color.system_accent3_400,
    android.R.color.system_accent3_500,
    android.R.color.system_accent3_600,
    android.R.color.system_accent3_700,
    android.R.color.system_accent3_800,
    android.R.color.system_accent3_900,
    android.R.color.system_accent3_1000
)

var COLOR_NAMES = arrayOf(
    "N1", "N2", "A1", "A2", "A3"
)

var COLORS = arrayOf(
    COLORS_NEUTRAL1,
    COLORS_NEUTRAL2,
    COLORS_ACCENT1,
    COLORS_ACCENT2,
    COLORS_ACCENT3
)

internal fun updateAppWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int
) {
    // val opts = appWidgetManager.getAppWidgetOptions(appWidgetId)
    // Log.v(TAG, "requested sizes=${opts[OPTION_APPWIDGET_SIZES]}")

    val allSizes = mapOf(
        SizeF(50f, 50f)
                to buildWidget(context, 1, 1, ClickBehavior.LAUNCH),
        SizeF(100f, 50f)
                to buildWidget(context, 1, 2, ClickBehavior.LAUNCH),
        SizeF(150f, 50f)
                to buildWidget(context, 1, 3, ClickBehavior.LAUNCH),
        SizeF(200f, 50f)
                to buildWidget(context, 1, 4, ClickBehavior.LAUNCH),
        SizeF(250f, 50f)
                to buildWidget(context, 1, 5, ClickBehavior.LAUNCH),

        SizeF(50f, 120f)
                to buildWidget(context, 3, 1, ClickBehavior.LAUNCH),
        SizeF(100f, 120f)
                to buildWidget(context, 3, 2, ClickBehavior.LAUNCH),
        SizeF(150f, 120f)
                to buildWidget(context, 3, 3, ClickBehavior.LAUNCH),
        SizeF(200f, 120f)
                to buildWidget(context, 3, 4, ClickBehavior.LAUNCH),
        SizeF(250f, 120f)
                to buildWidget(context, 3, 5, ClickBehavior.LAUNCH),

        SizeF(50f, 250f)
                to buildWidget(context, 5, 1, ClickBehavior.LAUNCH),
        SizeF(100f, 250f)
                to buildWidget(context, 5, 2, ClickBehavior.LAUNCH),
        SizeF(150f, 250f)
                to buildWidget(context, 5, 3, ClickBehavior.LAUNCH),
        SizeF(200f, 250f)
                to buildWidget(context, 5, 4, ClickBehavior.LAUNCH),
        SizeF(250f, 250f)
                to buildWidget(context, 5, 5, ClickBehavior.LAUNCH),

        SizeF(300f, 300f)
                to buildWidget(context, SHADE_NUMBERS.size, COLORS.size, ClickBehavior.LAUNCH)
    )

    // Instruct the widget manager to update the widget
    appWidgetManager.updateAppWidget(appWidgetId, RemoteViews(allSizes))
}

fun buildFullWidget(context: Context, clickable: ClickBehavior): RemoteViews {
    return buildWidget(context, SHADE_NUMBERS.size, COLORS.size, clickable)
}

fun buildWidget(context: Context, numShades: Int, numColors: Int, clickable: ClickBehavior):
        RemoteViews {
    val grid = RemoteViews(context.packageName, R.layout.paint_chips_grid)

    // shouldn't be necessary but sometimes the RV instructions get played twice in launcher.
    grid.removeAllViews(R.id.paint_grid)

    grid.setInt(R.id.paint_grid, "setRowCount", numShades)
    grid.setInt(R.id.paint_grid, "setColumnCount", numColors)

    Log.v(TAG, "building widget: shade rows=$numShades, color columns=$numColors")

    COLORS.forEachIndexed colorLoop@{ i, colorlist ->
        when (colorlist) {
            COLORS_NEUTRAL1 -> if (numColors < 2) return@colorLoop
            COLORS_NEUTRAL2 -> if (numColors < 4) return@colorLoop
            COLORS_ACCENT2 -> if (numColors < 3) return@colorLoop
            COLORS_ACCENT3 -> if (numColors < 5) return@colorLoop
            else -> {} // always do ACCENT1
        }
        colorlist.forEachIndexed shadeLoop@{ j, resId ->
            when (SHADE_NUMBERS[j]) {
                500 -> {}
                300, 700 -> if (numShades < 3) return@shadeLoop
                100, 900 -> if (numShades < 5) return@shadeLoop
                else -> if (numShades < SHADE_NUMBERS.size) return@shadeLoop
            }
            val cell = RemoteViews(context.packageName, R.layout.paint_chip)
            cell.setTextViewText(R.id.chip, "${COLOR_NAMES[i]}-${SHADE_NUMBERS[j]}")
            val textColor = if (SHADE_NUMBERS[j] > 500)
                    colorlist[0]
                    else colorlist[colorlist.size - 1]
            cell.setTextColor(R.id.chip, context.getColor(textColor))
            cell.setColorStateList(R.id.chip, "setBackgroundTintList", resId)
            val text = """
                    ${COLOR_NAMES[i]}-${SHADE_NUMBERS[j]} (@${
                    context.resources.getResourceName(resId) })
                    currently: #${ String.format("%06x", context.getColor(resId) and 0xFFFFFF) }
                    """.trimIndent()
            when (clickable) {
                ClickBehavior.SHARE -> cell.setOnClickPendingIntent(
                    R.id.chip,
                    makeTextSharePendingIntent(context, text)
                )
                ClickBehavior.LAUNCH -> cell.setOnClickPendingIntent(
                    R.id.chip,
                    makeActivityLaunchPendingIntent(context)
                )
                ClickBehavior.NONE -> { }
            }
            grid.addView(R.id.paint_grid, cell)
        }
    }

    return grid
}

enum class ClickBehavior {
    NONE,
    SHARE,
    LAUNCH
}

fun makeTextSharePendingIntent(context: Context, text: String): PendingIntent {
    val shareIntent: Intent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, text)
        type = "text/plain"
    }

    val chooserIntent = Intent.createChooser(shareIntent, null).apply {
        identifier = text // incredible quality-of-life improvement, thanks framework team
    }

    return PendingIntent.getActivity(context, 0, chooserIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

fun makeActivityLaunchPendingIntent(context: Context): PendingIntent {
    return PendingIntent.getActivity(context, 0,
        Intent().apply {
            component = ComponentName(context, PaintChipsActivity::class.java)
            action = Intent.ACTION_MAIN
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
