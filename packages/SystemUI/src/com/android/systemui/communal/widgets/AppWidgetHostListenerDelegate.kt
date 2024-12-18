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

import android.appwidget.AppWidgetHost.AppWidgetHostListener
import android.appwidget.AppWidgetProviderInfo
import android.widget.RemoteViews
import com.android.systemui.dagger.qualifiers.Main
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.Executor

/**
 * Wrapper for an [AppWidgetHostListener] to ensure the callbacks are executed on the main thread.
 */
class AppWidgetHostListenerDelegate
@AssistedInject
constructor(
    @Main private val mainExecutor: Executor,
    @Assisted private val listener: AppWidgetHostListener,
) : AppWidgetHostListener {

    @AssistedFactory
    interface Factory {
        fun create(listener: AppWidgetHostListener): AppWidgetHostListenerDelegate
    }

    override fun onUpdateProviderInfo(appWidget: AppWidgetProviderInfo?) {
        mainExecutor.execute { listener.onUpdateProviderInfo(appWidget) }
    }

    override fun updateAppWidget(views: RemoteViews?) {
        mainExecutor.execute { listener.updateAppWidget(views) }
    }

    override fun onViewDataChanged(viewId: Int) {
        mainExecutor.execute { listener.onViewDataChanged(viewId) }
    }
}
