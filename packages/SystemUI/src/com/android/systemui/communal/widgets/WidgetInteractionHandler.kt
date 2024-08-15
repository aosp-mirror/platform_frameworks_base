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
import com.android.app.tracing.coroutines.launch
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.Flags.communalWidgetTrampolineFix
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.domain.interactor.WidgetTrampolineInteractor
import com.android.systemui.communal.util.InteractionHandlerDelegate
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

@SysUISingleton
class WidgetInteractionHandler
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @UiBackground private val uiBackgroundContext: CoroutineContext,
    private val activityStarter: ActivityStarter,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    communalSceneInteractor: CommunalSceneInteractor,
    private val widgetTrampolineInteractor: WidgetTrampolineInteractor,
    @CommunalLog val logBuffer: LogBuffer,
) : RemoteViews.InteractionHandler {

    private companion object {
        const val TAG = "WidgetInteractionHandler"
    }

    private val delegate =
        InteractionHandlerDelegate(
            communalSceneInteractor,
            findViewToAnimate = { view -> view is CommunalAppWidgetHostView },
            intentStarter =
                object : InteractionHandlerDelegate.IntentStarter {
                    private var job: Job? = null

                    override fun startActivity(
                        intent: PendingIntent,
                        fillInIntent: Intent,
                        activityOptions: ActivityOptions,
                        controller: ActivityTransitionAnimator.Controller?
                    ): Boolean {
                        cancelTrampolineMonitoring()
                        return startActivityIntent(
                            intent,
                            fillInIntent,
                            activityOptions,
                            controller
                        )
                    }

                    override fun startPendingIntent(
                        view: View,
                        pendingIntent: PendingIntent,
                        fillInIntent: Intent,
                        activityOptions: ActivityOptions
                    ): Boolean {
                        cancelTrampolineMonitoring()
                        if (communalWidgetTrampolineFix()) {
                            job =
                                applicationScope.launch("$TAG#monitorForActivityStart") {
                                    widgetTrampolineInteractor
                                        .waitForActivityStartAndDismissKeyguard()
                                }
                        }
                        return super.startPendingIntent(
                            view,
                            pendingIntent,
                            fillInIntent,
                            activityOptions
                        )
                    }

                    private fun cancelTrampolineMonitoring() {
                        job?.cancel()
                        job = null
                    }
                },
            logger = Logger(logBuffer, TAG),
        )

    override fun onInteraction(
        view: View,
        pendingIntent: PendingIntent,
        response: RemoteViews.RemoteResponse
    ): Boolean = delegate.onInteraction(view, pendingIntent, response)

    private fun startActivityIntent(
        pendingIntent: PendingIntent,
        fillInIntent: Intent,
        extraOptions: ActivityOptions,
        controller: ActivityTransitionAnimator.Controller?
    ): Boolean {
        activityStarter.startPendingIntentMaybeDismissingKeyguard(
            pendingIntent,
            /* dismissShade = */ false,
            {
                applicationScope.launch("$TAG#awakenFromDream", uiBackgroundContext) {
                    // This activity could have started while the device is dreaming, in which case
                    // the dream would occlude the activity. In order to show the newly started
                    // activity, we wake from the dream.
                    keyguardUpdateMonitor.awakenFromDream()
                }
            },
            controller,
            fillInIntent,
            extraOptions.toBundle(),
            // TODO(b/325110448): UX to provide copy
            /* customMessage = */ null,
        )
        return true
    }
}
