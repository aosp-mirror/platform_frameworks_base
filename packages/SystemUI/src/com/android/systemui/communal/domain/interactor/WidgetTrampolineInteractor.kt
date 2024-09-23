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

package com.android.systemui.communal.domain.interactor

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.DreamManager
import com.android.systemui.common.usagestats.domain.UsageStatsInteractor
import com.android.systemui.common.usagestats.shared.model.ActivityEventModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.util.kotlin.race
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Detects activity starts that occur while the communal hub is showing, within a short delay of a
 * widget interaction occurring. Used for detecting non-activity trampolines which otherwise would
 * not prompt the user for authentication.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class WidgetTrampolineInteractor
@Inject
constructor(
    private val activityStarter: ActivityStarter,
    private val systemClock: SystemClock,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val taskStackChangeListeners: TaskStackChangeListeners,
    private val usageStatsInteractor: UsageStatsInteractor,
    private val dreamManager: DreamManager,
    @Background private val bgScope: CoroutineScope,
    @CommunalLog logBuffer: LogBuffer,
) {
    private companion object {
        const val TAG = "WidgetTrampolineInteractor"
    }

    private val logger = Logger(logBuffer, TAG)

    /** Waits for a new task to be moved to the foreground. */
    private suspend fun waitForNewForegroundTask() = suspendCancellableCoroutine { cont ->
        val listener =
            object : TaskStackChangeListener {
                override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
                    if (!cont.isCompleted) {
                        cont.resume(Unit, null)
                    }
                }
            }
        taskStackChangeListeners.registerTaskStackListener(listener)
        cont.invokeOnCancellation { taskStackChangeListeners.unregisterTaskStackListener(listener) }
    }

    /**
     * Waits for an activity to enter a [ActivityEventModel.Lifecycle.RESUMED] state by periodically
     * polling the system to see if any activities have started.
     */
    private suspend fun waitForActivityStartByPolling(startTime: Long): Boolean {
        while (true) {
            val events = usageStatsInteractor.queryActivityEvents(startTime = startTime)
            if (events.any { event -> event.lifecycle == ActivityEventModel.Lifecycle.RESUMED }) {
                return true
            } else {
                // Poll again in the future to check if an activity started.
                delay(200.milliseconds)
            }
        }
    }

    /** Waits for a transition away from the hub to occur. */
    private suspend fun waitForTransitionAwayFromHub() {
        keyguardTransitionInteractor
            .isFinishedIn(Scenes.Communal, KeyguardState.GLANCEABLE_HUB)
            .takeWhile { it }
            .collect {}
    }

    private suspend fun waitForActivityStartWhileOnHub(): Boolean {
        val startTime = systemClock.currentTimeMillis()
        return try {
            return withTimeout(1.seconds) {
                race(
                    {
                        waitForNewForegroundTask()
                        true
                    },
                    { waitForActivityStartByPolling(startTime) },
                    {
                        waitForTransitionAwayFromHub()
                        false
                    },
                )
            }
        } catch (e: TimeoutCancellationException) {
            false
        }
    }

    /**
     * Checks if an activity starts while on the glanceable hub and dismisses the keyguard if it
     * does. This can detect activities started due to broadcast trampolines from widgets.
     */
    @SuppressLint("MissingPermission")
    suspend fun waitForActivityStartAndDismissKeyguard() {
        if (waitForActivityStartWhileOnHub()) {
            logger.d("Detected trampoline, requesting unlock")
            activityStarter.dismissKeyguardThenExecute(
                /* action= */ {
                    // Kill the dream when launching the trampoline activity. Right now the exit
                    // animation stalls when tapping the battery widget, and the dream remains
                    // visible until the transition hits some timeouts and gets cancelled.
                    // TODO(b/362841648): remove once exit animation is fixed.
                    bgScope.launch { dreamManager.stopDream() }
                    false
                },
                /* cancel= */ null,
                /* afterKeyguardGone= */ false,
            )
        }
    }
}
