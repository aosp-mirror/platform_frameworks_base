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
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.communal.util.InteractionHandlerDelegate
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Inject

@SysUISingleton
class WidgetInteractionHandler
@Inject
constructor(
    private val activityStarter: ActivityStarter,
) : RemoteViews.InteractionHandler {

    private val delegate =
        InteractionHandlerDelegate(
            findViewToAnimate = { view -> view is CommunalAppWidgetHostView },
            intentStarter = this::startIntent,
        )

    override fun onInteraction(
        view: View,
        pendingIntent: PendingIntent,
        response: RemoteViews.RemoteResponse
    ): Boolean = delegate.onInteraction(view, pendingIntent, response)

    private fun startIntent(
        pendingIntent: PendingIntent,
        fillInIntent: Intent,
        extraOptions: ActivityOptions,
        controller: ActivityTransitionAnimator.Controller?
    ): Boolean {
        activityStarter.startPendingIntentMaybeDismissingKeyguard(
            pendingIntent,
            /* dismissShade = */ false,
            /* intentSentUiThreadCallback = */ null,
            controller,
            fillInIntent,
            extraOptions.toBundle(),
            // TODO(b/325110448): UX to provide copy
            /* customMessage = */ null,
        )
        return true
    }
}
