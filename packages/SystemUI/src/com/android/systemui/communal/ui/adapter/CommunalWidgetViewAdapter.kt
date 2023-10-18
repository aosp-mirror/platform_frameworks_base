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

package com.android.systemui.communal.ui.adapter

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.SizeF
import com.android.systemui.communal.shared.model.CommunalAppWidgetInfo
import com.android.systemui.communal.ui.view.CommunalWidgetWrapper
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Transforms a [CommunalAppWidgetInfo] to a view that renders the widget. */
class CommunalWidgetViewAdapter
@Inject
constructor(
    @Application private val context: Context,
    private val appWidgetManager: AppWidgetManager,
    private val appWidgetHost: AppWidgetHost,
    @CommunalLog logBuffer: LogBuffer,
) {
    companion object {
        private const val TAG = "CommunalWidgetViewAdapter"
    }

    private val logger = Logger(logBuffer, TAG)

    fun adapt(providerInfoFlow: Flow<CommunalAppWidgetInfo?>): Flow<CommunalWidgetWrapper?> =
        providerInfoFlow.map {
            if (it == null) {
                return@map null
            }

            val appWidgetId = it.appWidgetId
            val providerInfo = it.providerInfo

            if (appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, providerInfo.provider)) {
                logger.d("Success binding app widget id: $appWidgetId")
                return@map CommunalWidgetWrapper(context).apply {
                    addView(
                        appWidgetHost.createView(context, appWidgetId, providerInfo).apply {
                            // Set the widget to minimum width and height
                            updateAppWidgetSize(
                                appWidgetManager.getAppWidgetOptions(appWidgetId),
                                listOf(
                                    SizeF(
                                        providerInfo.minResizeWidth.toFloat(),
                                        providerInfo.minResizeHeight.toFloat()
                                    )
                                )
                            )
                        }
                    )
                }
            } else {
                logger.w("Failed binding app widget id")
                return@map null
            }
        }
}
