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

import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE
import android.content.pm.ApplicationInfo
import android.widget.RemoteViews
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import java.util.UUID

/** Encapsulates data for a communal content. */
sealed interface CommunalContentModel {
    /** Unique key across all types of content models. */
    val key: String

    /** Size to be rendered in the grid. */
    val size: CommunalContentSize

    /**
     * A type of communal content is ongoing / live / ephemeral, and can be sized and ordered
     * dynamically.
     */
    sealed interface Ongoing : CommunalContentModel {
        override var size: CommunalContentSize

        /** Timestamp in milliseconds of when the content was created. */
        val createdTimestampMillis: Long
    }

    sealed interface WidgetContent : CommunalContentModel {
        val appWidgetId: Int
        val providerInfo: AppWidgetProviderInfo

        data class Widget(
            override val appWidgetId: Int,
            override val providerInfo: AppWidgetProviderInfo,
            val appWidgetHost: CommunalAppWidgetHost,
            val inQuietMode: Boolean,
        ) : WidgetContent {
            override val key = KEY.widget(appWidgetId)
            // Widget size is always half.
            override val size = CommunalContentSize.HALF

            /** Whether this widget can be reconfigured after it has already been added. */
            val reconfigurable: Boolean
                get() =
                    (providerInfo.widgetFeatures and WIDGET_FEATURE_RECONFIGURABLE != 0) &&
                        providerInfo.configure != null
        }

        data class DisabledWidget(
            override val appWidgetId: Int,
            override val providerInfo: AppWidgetProviderInfo
        ) : WidgetContent {
            override val key = KEY.disabledWidget(appWidgetId)
            // Widget size is always half.
            override val size = CommunalContentSize.HALF

            val appInfo: ApplicationInfo?
                get() = providerInfo.providerInfo?.applicationInfo
        }
    }

    /** A placeholder item representing a new widget being added */
    class WidgetPlaceholder : CommunalContentModel {
        override val key: String = KEY.widgetPlaceholder()
        // Same as widget size.
        override val size = CommunalContentSize.HALF
    }

    /** A CTA tile in the glanceable hub view mode which can be dismissed. */
    class CtaTileInViewMode : CommunalContentModel {
        override val key: String = KEY.CTA_TILE_IN_VIEW_MODE_KEY
        // Same as widget size.
        override val size = CommunalContentSize.HALF
    }

    /** A CTA tile in the glanceable hub edit model which remains visible in the grid. */
    class CtaTileInEditMode : CommunalContentModel {
        override val key: String = KEY.CTA_TILE_IN_EDIT_MODE_KEY
        // Same as widget size.
        override val size = CommunalContentSize.HALF
    }

    class Tutorial(
        id: Int,
        override var size: CommunalContentSize,
    ) : CommunalContentModel {
        override val key = KEY.tutorial(id)
    }

    class Smartspace(
        smartspaceTargetId: String,
        val remoteViews: RemoteViews,
        override val createdTimestampMillis: Long,
        override var size: CommunalContentSize = CommunalContentSize.HALF,
    ) : Ongoing {
        override val key = KEY.smartspace(smartspaceTargetId)
    }

    class Umo(
        override val createdTimestampMillis: Long,
        override var size: CommunalContentSize = CommunalContentSize.HALF,
    ) : Ongoing {
        override val key = KEY.umo()
    }

    class KEY {
        companion object {
            const val CTA_TILE_IN_VIEW_MODE_KEY = "cta_tile_in_view_mode"
            const val CTA_TILE_IN_EDIT_MODE_KEY = "cta_tile_in_edit_mode"

            fun widget(id: Int): String {
                return "widget_$id"
            }

            fun disabledWidget(id: Int): String {
                return "disabled_widget_$id"
            }

            fun widgetPlaceholder(): String {
                return "widget_placeholder_${UUID.randomUUID()}"
            }

            fun tutorial(id: Int): String {
                return "tutorial_$id"
            }

            fun smartspace(id: String): String {
                return "smartspace_$id"
            }

            fun umo(): String {
                return "umo"
            }
        }
    }

    fun isWidgetContent() = this is WidgetContent

    fun isSmartspace() = this is Smartspace
}
