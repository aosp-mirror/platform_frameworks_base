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

package com.android.systemui.communal.domain.model

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetProviderInfo
import android.widget.RemoteViews
import com.android.systemui.communal.shared.model.CommunalContentSize

/** Encapsulates data for a communal content. */
sealed interface CommunalContentModel {
    /** Unique key across all types of content models. */
    val key: String

    /** Size to be rendered in the grid. */
    val size: CommunalContentSize

    class Widget(
        val appWidgetId: Int,
        val providerInfo: AppWidgetProviderInfo,
        val appWidgetHost: AppWidgetHost,
    ) : CommunalContentModel {
        override val key = "widget_$appWidgetId"
        // Widget size is always half.
        override val size = CommunalContentSize.HALF
    }

    class Tutorial(
        id: Int,
        override val size: CommunalContentSize,
    ) : CommunalContentModel {
        override val key = "tutorial_$id"
    }

    class Smartspace(
        smartspaceTargetId: String,
        val remoteViews: RemoteViews,
        override val size: CommunalContentSize,
    ) : CommunalContentModel {
        override val key = "smartspace_$smartspaceTargetId"
    }
}
