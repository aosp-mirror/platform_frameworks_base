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
package com.android.systemui.shade

import android.util.Log
import com.android.app.tracing.coroutines.TrackTracer
import com.android.internal.util.LatencyTracker
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.common.ui.view.ChoreographerUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.util.kotlin.getOrNull
import java.util.Optional
import java.util.concurrent.CancellationException
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Tracks the time it takes to move the shade from one display to another.
 * - The start event is when [ShadeDisplaysRepository] propagates the new display ID.
 * - The end event is one frame after the shade configuration controller receives a new
 *   configuration change.
 *
 * Note that even in the unlikely case the configuration of the new display is the same,
 * onConfigurationChange is called anyway as is is triggered by
 * [NotificationShadeWindowView.onMovedToDisplay].
 */
@SysUISingleton
class ShadeDisplayChangeLatencyTracker
@Inject
constructor(
    optionalShadeRootView: Optional<WindowRootView>,
    @ShadeDisplayAware private val configurationRepository: ConfigurationRepository,
    private val latencyTracker: LatencyTracker,
    @Background private val bgScope: CoroutineScope,
    private val choreographerUtils: ChoreographerUtils,
) {

    private val shadeRootView =
        optionalShadeRootView.getOrNull()
            ?: error(
                """
            ShadeRootView must be provided for ShadeDisplayChangeLatencyTracker to work.
            If it is not, it means this is being instantiated in a SystemUI variant that shouldn't.
            """
                    .trimIndent()
            )
    /**
     * We need to keep this always up to date eagerly to avoid delays receiving the new display ID.
     */
    private val onMovedToDisplayFlow: StateFlow<Int> = configurationRepository.onMovedToDisplay

    private var previousJob: Job? = null

    /**
     * Called before the display change begins.
     *
     * It is guaranteed that context and resources are still associated to the "old" display id, and
     * that onMovedToDisplay has not been received yet on the notification shade window root view.
     *
     * IMPORTANT: this shouldn't be refactored to use [ShadePositionRepository], otherwise there is
     * no guarantees of event order (as the shade could be reparented before the event is propagated
     * to this class, breaking the assumption that [onMovedToDisplayFlow] didn't emit with the new
     * display id yet.
     */
    @Synchronized
    fun onShadeDisplayChanging(displayId: Int) {
        previousJob?.cancel(CancellationException("New shade move in progress to $displayId"))
        previousJob = bgScope.launch { onShadeDisplayChangingAsync(displayId) }
    }

    private suspend fun onShadeDisplayChangingAsync(displayId: Int) {
        try {
            latencyTracker.onActionStart(SHADE_MOVE_ACTION)
            waitForOnMovedToDisplayDispatchedToView(displayId)
            waitUntilNextDoFrameDone()
            latencyTracker.onActionEnd(SHADE_MOVE_ACTION)
        } catch (e: Exception) {
            val reason =
                when (e) {
                    is CancellationException ->
                        "Shade move to $displayId cancelled as a new move is being done " +
                            "before the previous one finished. Message: ${e.message}"

                    else -> "Shade move cancelled."
                }
            Log.e(TAG, reason, e)
            latencyTracker.onActionCancel(SHADE_MOVE_ACTION)
        }
    }

    private suspend fun waitForOnMovedToDisplayDispatchedToView(newDisplayId: Int) {
        t.traceAsync({ "waitForOnMovedToDisplayDispatchedToView(newDisplayId=$newDisplayId)" }) {
            withTimeout(TIMEOUT) { onMovedToDisplayFlow.filter { it == newDisplayId }.first() }
            t.instant { "onMovedToDisplay received with $newDisplayId" }
        }
    }

    private suspend fun waitUntilNextDoFrameDone(): Unit =
        t.traceAsync("waitUntilNextDoFrameDone") {
            withTimeout(TIMEOUT) { choreographerUtils.waitUntilNextDoFrameDone(shadeRootView) }
        }

    private companion object {
        const val TAG = "ShadeDisplayLatency"
        val t = TrackTracer(trackName = TAG, trackGroup = "shade")
        val TIMEOUT = 3.seconds
        const val SHADE_MOVE_ACTION = LatencyTracker.ACTION_SHADE_WINDOW_DISPLAY_CHANGE
    }
}
