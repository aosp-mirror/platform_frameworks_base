/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Looper
import android.widget.RemoteViews

/** Communal app widget host that creates a [CommunalAppWidgetHostView]. */
class CommunalAppWidgetHost(
    context: Context,
    hostId: Int,
    interactionHandler: RemoteViews.InteractionHandler,
    looper: Looper
) : AppWidgetHost(context, hostId, interactionHandler, looper) {
    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView {
        return CommunalAppWidgetHostView(context)
    }

    /**
     * Creates and returns a [CommunalAppWidgetHostView]. This method does the same thing as
     * `createView`. The only difference is that the returned value will be casted to
     * [CommunalAppWidgetHostView].
     */
    fun createViewForCommunal(
        context: Context?,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): CommunalAppWidgetHostView {
        // `createView` internally calls `onCreateView` to create the view. We cannot override
        // `createView`, but we are sure that the hostView is `CommunalAppWidgetHostView`
        return createView(context, appWidgetId, appWidget) as CommunalAppWidgetHostView
    }
}
