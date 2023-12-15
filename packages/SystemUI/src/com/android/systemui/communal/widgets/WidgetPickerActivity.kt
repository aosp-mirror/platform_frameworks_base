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
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.core.view.setMargins
import androidx.core.view.setPadding
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
        loadWidgets()
    }

    private fun loadWidgets() {
        val containerView: ViewGroup? = findViewById(R.id.widgets_container)
        containerView?.apply {
            try {
                appWidgetManager
                    .getInstalledProviders(AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)
                    ?.stream()
                    ?.forEach { widgetInfo ->
                        val activity = this@WidgetPickerActivity
                        (widgetInfo.loadPreviewImage(activity, 0)
                                ?: widgetInfo.loadIcon(activity, 0))
                            ?.let {
                                addView(
                                    ImageView(activity).also { v ->
                                        v.setImageDrawable(it)
                                        v.setBackgroundColor(WIDGET_PREVIEW_BACKGROUND_COLOR)
                                        v.setPadding(WIDGET_PREVIEW_PADDING)
                                        v.layoutParams =
                                            LinearLayout.LayoutParams(
                                                    WIDGET_PREVIEW_SIZE,
                                                    WIDGET_PREVIEW_SIZE
                                                )
                                                .also { lp ->
                                                    lp.setMargins(WIDGET_PREVIEW_MARGINS)
                                                }
                                        v.setOnClickListener {
                                            setResult(
                                                RESULT_OK,
                                                Intent()
                                                    .putExtra(
                                                        EditWidgetsActivity.ADD_WIDGET_INFO,
                                                        widgetInfo
                                                    )
                                            )
                                            finish()
                                        }
                                    }
                                )
                            }
                    }
            } catch (e: RuntimeException) {
                Log.e(TAG, "Exception fetching widget providers", e)
            }
        }
    }

    companion object {
        private const val WIDGET_PREVIEW_SIZE = 600
        private const val WIDGET_PREVIEW_MARGINS = 32
        private const val WIDGET_PREVIEW_PADDING = 32
        private val WIDGET_PREVIEW_BACKGROUND_COLOR = Color.rgb(216, 225, 220)
        private const val TAG = "WidgetPickerActivity"
    }
}
