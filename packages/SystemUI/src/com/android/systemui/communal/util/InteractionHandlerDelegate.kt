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
import android.util.Pair as UtilPair
import android.view.View
import android.widget.RemoteViews
import androidx.core.util.component1
import androidx.core.util.component2
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.widgets.CommunalTransitionAnimatorController
import com.android.systemui.log.core.Logger

/** A delegate that can be used to launch activities from [RemoteViews] */
class InteractionHandlerDelegate(
    private val communalSceneInteractor: CommunalSceneInteractor,
    private val findViewToAnimate: (View) -> Boolean,
    private val intentStarter: IntentStarter,
    private val logger: Logger,
) : RemoteViews.InteractionHandler {

    interface IntentStarter {
        /** Responsible for starting the pending intent for launching activities. */
        fun startActivity(
            intent: PendingIntent,
            fillInIntent: Intent,
            activityOptions: ActivityOptions,
            controller: ActivityTransitionAnimator.Controller?,
        ): Boolean

        /** Responsible for starting the pending intent for non-activity launches. */
        fun startPendingIntent(
            view: View,
            pendingIntent: PendingIntent,
            fillInIntent: Intent,
            activityOptions: ActivityOptions,
        ): Boolean {
            return RemoteViews.startPendingIntent(
                view,
                pendingIntent,
                UtilPair(fillInIntent, activityOptions),
            )
        }
    }

    override fun onInteraction(
        view: View,
        pendingIntent: PendingIntent,
        response: RemoteViews.RemoteResponse
    ): Boolean {
        logger.i({ "Starting $str1 ($str2)" }) {
            str1 = pendingIntent.toLoggingString()
            str2 = pendingIntent.creatorPackage
        }
        val (fillInIntent, activityOptions) = response.getLaunchOptions(view)
        return when {
            pendingIntent.isActivity -> {
                // Forward the fill-in intent and activity options retrieved from the response
                // to populate the pending intent, so that list items can launch respective
                // activities.
                val hostView = getNearestParent(view)
                val animationController =
                    hostView?.let(ActivityTransitionAnimator.Controller::fromView)?.let {
                        communalSceneInteractor.setIsLaunchingWidget(true)
                        CommunalTransitionAnimatorController(it, communalSceneInteractor)
                    }
                intentStarter.startActivity(
                    pendingIntent,
                    fillInIntent,
                    activityOptions,
                    animationController
                )
            }
            else ->
                intentStarter.startPendingIntent(view, pendingIntent, fillInIntent, activityOptions)
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

private fun PendingIntent.toLoggingString() =
    when {
        isActivity -> "activity"
        isBroadcast -> "broadcast"
        isForegroundService -> "fgService"
        isService -> "service"
        else -> "unknown"
    }
