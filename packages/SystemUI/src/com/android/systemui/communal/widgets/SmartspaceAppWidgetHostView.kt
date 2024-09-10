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

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import com.android.systemui.animation.LaunchableView
import com.android.systemui.animation.LaunchableViewDelegate

/** AppWidgetHostView that displays in communal hub to show smartspace content. */
class SmartspaceAppWidgetHostView(context: Context) : AppWidgetHostView(context), LaunchableView {
    private val launchableViewDelegate =
        LaunchableViewDelegate(
            this,
            superSetVisibility = { super.setVisibility(it) },
        )

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        super.setAppWidget(appWidgetId, info)
        setPadding(0, 0, 0, 0)
    }

    override fun getRemoteContextEnsuringCorrectCachedApkPath(): Context? {
        // Silence errors
        return null
    }

    override fun setShouldBlockVisibilityChanges(block: Boolean) =
        launchableViewDelegate.setShouldBlockVisibilityChanges(block)

    override fun setVisibility(visibility: Int) = launchableViewDelegate.setVisibility(visibility)
}
