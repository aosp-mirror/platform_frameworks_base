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

package com.android.systemui.communal.data.model

import android.appwidget.AppWidgetProviderInfo

/**
 * The widget categories to display on communal hub (where categories is a bitfield with values that
 * match those in {@link AppWidgetProviderInfo}).
 */
object CommunalWidgetCategories {
    /**
     * Categories that are allowed on communal hub.
     * - Use "or" operator for including multiple categories.
     */
    val includedCategories: Int
        get() {
            return AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN or
                AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD
        }

    /**
     * Categories to further filter included widgets by excluding certain opt-out categories.
     * - WIDGET_CATEGORY_NOT_KEYGUARD: widgets opted out of displaying on keyguard like surfaces.
     * - Use "and" operator for excluding multiple opt-out categories.
     */
    val excludedCategories: Int
        get() {
            return AppWidgetProviderInfo.WIDGET_CATEGORY_NOT_KEYGUARD.inv()
        }
}
