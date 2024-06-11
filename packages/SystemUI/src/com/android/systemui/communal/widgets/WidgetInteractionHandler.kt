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

import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetHostView
import android.content.Intent
import android.util.Pair
import android.view.View
import android.widget.RemoteViews
import androidx.core.util.component1
import androidx.core.util.component2
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.common.ui.view.getNearestParent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Inject

@SysUISingleton
class WidgetInteractionHandler
@Inject
constructor(
    private val activityStarter: ActivityStarter,
) : RemoteViews.InteractionHandler {
    override fun onInteraction(
        view: View,
        pendingIntent: PendingIntent,
        response: RemoteViews.RemoteResponse
    ): Boolean {
        val launchOptions = response.getLaunchOptions(view)
        return when {
            pendingIntent.isActivity ->
                // Forward the fill-in intent and activity options retrieved from the response
                // to populate the pending intent, so that list items can launch respective
                // activities.
                startActivity(view, pendingIntent, launchOptions)
            else -> RemoteViews.startPendingIntent(view, pendingIntent, launchOptions)
        }
    }

    private fun startActivity(
        view: View,
        pendingIntent: PendingIntent,
        launchOptions: Pair<Intent, ActivityOptions>,
    ): Boolean {
        val hostView = view.getNearestParent<AppWidgetHostView>()
        val animationController = hostView?.let(ActivityTransitionAnimator.Controller::fromView)
        val (fillInIntent, activityOptions) = launchOptions

        activityStarter.startPendingIntentMaybeDismissingKeyguard(
            pendingIntent,
            /* dismissShade = */ false,
            /* intentSentUiThreadCallback = */ null,
            animationController,
            fillInIntent,
            activityOptions.toBundle(),
        )
        return true
    }
}
