/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.statusbar.notification.stack.domain.interactor

import android.graphics.Rect
import android.util.Log
import com.android.app.tracing.FlowTracing.traceEach
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState.SCREEN_ON
import com.android.systemui.unfold.domain.interactor.UnfoldTransitionInteractor
import com.android.systemui.util.animation.data.repository.AnimationStatusRepository
import com.android.systemui.util.kotlin.WithPrev
import com.android.systemui.util.kotlin.area
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.kotlin.race
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class HideNotificationsInteractor
@Inject
constructor(
    private val unfoldTransitionInteractor: UnfoldTransitionInteractor,
    private val configurationInteractor: ConfigurationInteractor,
    private val animationsStatus: AnimationStatusRepository,
    private val powerInteractor: PowerInteractor
) {

    val shouldHideNotifications: Flow<Boolean>
        get() =
            if (!unfoldTransitionInteractor.isAvailable) {
                // Do nothing on non-foldable devices
                emptyFlow()
            } else {
                screenSizeChangesFlow
                    .flatMapLatest {
                        flow {
                            // Hide notifications on each display resize
                            emit(true)
                            try {
                                waitForDisplaySwitchFinish(it)
                            } catch (_: TimeoutCancellationException) {
                                Log.e(TAG, "Timed out waiting for display switch")
                            } finally {
                                emit(false)
                            }
                        }
                    }
                    .distinctUntilChanged()
                    .traceEach(HIDE_STATUS_TRACK_NAME, logcat = true) { shouldHide ->
                        if (shouldHide) "hidden" else "visible"
                    }
            }

    private suspend fun waitForDisplaySwitchFinish(screenSizeChange: WithPrev<Rect, Rect>) {
        withTimeout(timeMillis = DISPLAY_SWITCH_TIMEOUT_MILLIS) {
            val waitForDisplaySwitchOrAnimation: suspend () -> Unit = {
                if (shouldWaitForAnimationEnd(screenSizeChange)) {
                    unfoldTransitionInteractor.waitForTransitionFinish()
                } else {
                    waitForScreenTurnedOn()
                }
            }

            race({ waitForDisplaySwitchOrAnimation() }, { waitForGoingToSleep() })
        }
    }

    private suspend fun shouldWaitForAnimationEnd(screenSizeChange: WithPrev<Rect, Rect>): Boolean =
        animationsStatus.areAnimationsEnabled().first() && screenSizeChange.isUnfold

    private suspend fun waitForScreenTurnedOn() =
        powerInteractor.screenPowerState.filter { it == SCREEN_ON }.first()

    private suspend fun waitForGoingToSleep() =
        powerInteractor.detailedWakefulness.filter { it.isAsleep() }.first()

    private val screenSizeChangesFlow: Flow<WithPrev<Rect, Rect>>
        get() = configurationInteractor.naturalMaxBounds.pairwise()

    private val WithPrev<Rect, Rect>.isUnfold: Boolean
        get() = newValue.area > previousValue.area

    private companion object {
        private const val TAG = "DisplaySwitchNotificationsHideInteractor"
        private const val HIDE_STATUS_TRACK_NAME = "NotificationsHiddenForDisplayChange"
        private const val DISPLAY_SWITCH_TIMEOUT_MILLIS = 5_000L
    }
}
