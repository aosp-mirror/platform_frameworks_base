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

package com.android.systemui.shade.display

import android.util.Log
import android.view.Display
import android.view.MotionEvent
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.shade.domain.interactor.NotificationShadeElement
import com.android.systemui.shade.domain.interactor.QSShadeElement
import com.android.systemui.shade.domain.interactor.ShadeExpandedStateInteractor.ShadeElement
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import dagger.Lazy
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Moves the shade on the last display that received a status bar touch.
 *
 * If the display is removed, falls back to the default one. When [shadeOnDefaultDisplayWhenLocked]
 * is true, the shade falls back to the default display when the keyguard is visible.
 */
@SysUISingleton
class StatusBarTouchShadeDisplayPolicy
@Inject
constructor(
    displayRepository: DisplayRepository,
    @Background private val backgroundScope: CoroutineScope,
    private val shadeInteractor: Lazy<ShadeInteractor>,
    private val qsShadeElement: Lazy<QSShadeElement>,
    private val notificationElement: Lazy<NotificationShadeElement>,
) : ShadeDisplayPolicy, ShadeExpansionIntent {
    override val name: String = "status_bar_latest_touch"

    private val currentDisplayId = MutableStateFlow(Display.DEFAULT_DISPLAY)
    private val availableDisplayIds: StateFlow<Set<Int>> = displayRepository.displayIds

    private var latestIntent = AtomicReference<ShadeElement?>()
    private var timeoutJob: Job? = null

    override val displayId: StateFlow<Int> = currentDisplayId

    private var removalListener: Job? = null

    /** Called when the status bar on the given display is touched. */
    fun onStatusBarTouched(event: MotionEvent, statusBarWidth: Int) {
        ShadeWindowGoesAround.isUnexpectedlyInLegacyMode()
        updateShadeDisplayIfNeeded(event)
        updateExpansionIntent(event, statusBarWidth)
    }

    override fun consumeExpansionIntent(): ShadeElement? {
        return latestIntent.getAndSet(null)
    }

    private fun updateExpansionIntent(event: MotionEvent, statusBarWidth: Int) {
        val element = classifyStatusBarEvent(event, statusBarWidth)
        latestIntent.set(element)
        timeoutJob?.cancel()
        timeoutJob =
            backgroundScope.launchTraced("StatusBarTouchDisplayPolicy#intentTimeout") {
                delay(EXPANSION_INTENT_EXPIRY)
                latestIntent.set(null)
            }
    }

    private fun updateShadeDisplayIfNeeded(event: MotionEvent) {
        val statusBarDisplayId = event.displayId
        if (statusBarDisplayId !in availableDisplayIds.value) {
            Log.e(TAG, "Got touch on unknown display $statusBarDisplayId")
            return
        }
        currentDisplayId.value = statusBarDisplayId
        if (removalListener == null) {
            // Lazy start this at the first invocation. it's fine to let it run also when the policy
            // is not selected anymore, as the job doesn't do anything until someone subscribes to
            // displayId.
            removalListener = monitorDisplayRemovals()
        }
    }

    private fun classifyStatusBarEvent(
        motionEvent: MotionEvent,
        statusbarWidth: Int,
    ): ShadeElement {
        val xPercentage = motionEvent.x / statusbarWidth
        val threshold = shadeInteractor.get().getTopEdgeSplitFraction()
        return if (xPercentage < threshold) {
            notificationElement.get()
        } else qsShadeElement.get()
    }

    private fun monitorDisplayRemovals(): Job {
        return backgroundScope.launchTraced("StatusBarTouchDisplayPolicy#monitorDisplayRemovals") {
            currentDisplayId.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                // When Active is false, no collect happens, and the old one is cancelled.
                // This is needed to prevent "availableDisplayIds" collection while nobody is
                // listening at the flow provided by this class.
                .collectLatest { active ->
                    if (active) {
                        availableDisplayIds.collect { availableIds ->
                            if (currentDisplayId.value !in availableIds) {
                                currentDisplayId.value = Display.DEFAULT_DISPLAY
                            }
                        }
                    }
                }
        }
    }

    private companion object {
        const val TAG = "StatusBarTouchDisplayPolicy"
        val EXPANSION_INTENT_EXPIRY = 2.seconds
    }
}
