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

package com.android.systemui.communal.util

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.util.component1
import androidx.core.util.component2
import com.android.systemui.animation.ActivityTransitionAnimator


/** A delegate that can be used to launch activities from [RemoteViews] */
class InteractionHandlerDelegate(
    private val findViewToAnimate: (View) -> Boolean,
    private val intentStarter: IntentStarter,
) : RemoteViews.InteractionHandler {

    /**
     * Responsible for starting the pending intent for launching activities.
     */
    fun interface IntentStarter {
        fun startPendingIntent(
            intent: PendingIntent,
            fillInIntent: Intent,
            activityOptions: ActivityOptions,
            controller: ActivityTransitionAnimator.Controller?,
        ): Boolean
    }

    override fun onInteraction(
        view: View,
        pendingIntent: PendingIntent,
        response: RemoteViews.RemoteResponse
    ): Boolean {
        val launchOptions = response.getLaunchOptions(view)
        return when {
            pendingIntent.isActivity -> {
                // Forward the fill-in intent and activity options retrieved from the response
                // to populate the pending intent, so that list items can launch respective
                // activities.
                val hostView = getNearestParent(view)
                val animationController =
                    hostView?.let(ActivityTransitionAnimator.Controller::fromView)
                val (fillInIntent, activityOptions) = launchOptions
                intentStarter.startPendingIntent(
                    pendingIntent,
                    fillInIntent,
                    activityOptions,
                    animationController
                )
            }

            else -> RemoteViews.startPendingIntent(view, pendingIntent, launchOptions)
        }
    }

    private fun getNearestParent(child: View): View? {
        var view: Any? = child
        while (view is View) {
            if (findViewToAnimate(view)) return view
            view = view.parent
        }
        return null
    }
}
