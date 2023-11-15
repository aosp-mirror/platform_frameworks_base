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

package com.android.systemui.communal.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * An Activity responsible for displaying a list of widgets to add to the hub mode grid. This is
 * essentially a placeholder until Launcher's widget picker can be used.
 */
class WidgetPickerActivity
@Inject
constructor(
    private val appWidgetManager: AppWidgetManager,
) : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.widget_picker)
        setShowWhenLocked(true)

        loadWidgets()
    }

    private fun loadWidgets() {
        val containerView: ViewGroup? = findViewById(R.id.widgets_container)
        containerView?.apply {
            try {
                appWidgetManager
                    .getInstalledProviders(AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)
                    ?.stream()
                    ?.limit(5)
                    ?.forEach { widgetInfo ->
                        val activity = this@WidgetPickerActivity
                        val widgetPreview =
                            widgetInfo.loadPreviewImage(activity, DisplayMetrics.DENSITY_HIGH)
                        val widgetView = ImageView(activity)
                        val lp = LinearLayout.LayoutParams(WIDGET_PREVIEW_SIZE, WIDGET_PREVIEW_SIZE)
                        widgetView.setLayoutParams(lp)
                        widgetView.setImageDrawable(widgetPreview)
                        widgetView.setOnClickListener({
                            setResult(
                                RESULT_OK,
                                Intent().putExtra(EditWidgetsActivity.ADD_WIDGET_INFO, widgetInfo)
                            )
                            finish()
                        })

                        addView(widgetView)
                    }
            } catch (e: RuntimeException) {
                Log.e(TAG, "Exception fetching widget providers", e)
            }
        }
    }

    companion object {
        private const val WIDGET_PREVIEW_SIZE = 400
        private const val TAG = "WidgetPickerActivity"
    }
}
