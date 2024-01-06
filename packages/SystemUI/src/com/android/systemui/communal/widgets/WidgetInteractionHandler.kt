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

import android.app.PendingIntent
import android.view.View
import android.widget.RemoteViews
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Inject

class WidgetInteractionHandler
@Inject
constructor(
    private val activityStarter: ActivityStarter,
) : RemoteViews.InteractionHandler {
    override fun onInteraction(
        view: View,
        pendingIntent: PendingIntent,
        response: RemoteViews.RemoteResponse
    ): Boolean =
        when {
            pendingIntent.isActivity -> startActivity(pendingIntent)
            else ->
                RemoteViews.startPendingIntent(view, pendingIntent, response.getLaunchOptions(view))
        }

    private fun startActivity(pendingIntent: PendingIntent): Boolean {
        activityStarter.startPendingIntentMaybeDismissingKeyguard(
            /* intent = */ pendingIntent,
            /* intentSentUiThreadCallback = */ null,
            // TODO(b/318758390): Properly animate activities started from widgets.
            /* animationController = */ null
        )
        return true
    }
}
