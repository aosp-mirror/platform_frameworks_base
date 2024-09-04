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

package com.android.systemui.communal.shared.model

import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.graphics.Bitmap
import android.os.UserHandle

/** Encapsulates data for a communal widget. */
sealed interface CommunalWidgetContentModel {
    val appWidgetId: Int
    val rank: Int

    /** Widget is ready to display */
    data class Available(
        override val appWidgetId: Int,
        val providerInfo: AppWidgetProviderInfo,
        override val rank: Int,
    ) : CommunalWidgetContentModel

    /** Widget is pending installation */
    data class Pending(
        override val appWidgetId: Int,
        override val rank: Int,
        val componentName: ComponentName,
        val icon: Bitmap?,
        val user: UserHandle,
    ) : CommunalWidgetContentModel
}
