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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.app.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.keyguard.domain.interactor.KeyguardDismissActionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.ScrimAlpha
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.SysuiStatusBarStateController
import dagger.Lazy
import javax.inject.Inject
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** ALTERNATE and PRIMARY bouncers common animations */
@OptIn(ExperimentalCoroutinesApi::class)
class BouncerToGoneFlows
@Inject
constructor(
    private val statusBarStateController: SysuiStatusBarStateController,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
    private val keyguardDismissActionInteractor: Lazy<KeyguardDismissActionInteractor>,
    private val shadeInteractor: ShadeInteractor,
    private val animationFlow: KeyguardTransitionAnimationFlow,
) {
    /** Common fade for scrim alpha values during *BOUNCER->GONE */
    fun scrimAlpha(duration: Duration, fromState: KeyguardState): Flow<ScrimAlpha> {
        return if (ComposeBouncerFlags.isEnabled) {
            keyguardDismissActionInteractor
                .get()
                .willAnimateDismissActionOnLockscreen
                .flatMapLatest { createScrimAlphaFlow(duration, fromState) { it } }
        } else {
            createScrimAlphaFlow(
                duration,
                fromState,
                primaryBouncerInteractor::willRunDismissFromKeyguard
            )
        }
    }

    /**
     * When the shade is expanded, make sure that all notifications can be seen immediately during a
     * transition to GONE. This matters especially when the user has chosen to not show
     * notifications on the lockscreen and then pulls down the shade, which presents them with an
     * immediate auth prompt, followed by a notification animation.
     */
    fun showAllNotifications(duration: Duration, from: KeyguardState): Flow<Boolean> {
        var leaveShadeOpen = false
        return animationFlow
            .setup(
                duration = duration,
                edge =
                    if (from == PRIMARY_BOUNCER) {
                        Edge.INVALID
                    } else {
                        Edge.create(from = from, to = Scenes.Gone)
                    }
            )
            .setupWithoutSceneContainer(
                edge = Edge.create(from = from, to = GONE),
            )
            .sharedFlow(
                duration = duration,
                onStart = { leaveShadeOpen = statusBarStateController.leaveOpenOnKeyguardHide() },
                onStep = { if (leaveShadeOpen) 1f else 0f },
                onFinish = { 0f },
                onCancel = { 0f },
            )
            .map { it == 1f }
            .distinctUntilChanged()
    }

    private fun createScrimAlphaFlow(
        duration: Duration,
        fromState: KeyguardState,
        willRunAnimationOnKeyguard: () -> Boolean
    ): Flow<ScrimAlpha> {
        var isShadeExpanded = false
        var leaveShadeOpen: Boolean = false
        var willRunDismissFromKeyguard: Boolean = false
        val transitionAnimation =
            animationFlow
                .setup(
                    duration = duration,
                    edge =
                        if (fromState == PRIMARY_BOUNCER) {
                            Edge.INVALID
                        } else {
                            Edge.create(from = fromState, to = Scenes.Gone)
                        }
                )
                .setupWithoutSceneContainer(
                    edge = Edge.create(from = fromState, to = GONE),
                )

        return shadeInteractor.anyExpansion
            .map { it > 0f }
            .distinctUntilChanged()
            .flatMapLatest { isAnyExpanded ->
                transitionAnimation
                    .sharedFlow(
                        duration = duration,
                        interpolator = EMPHASIZED_ACCELERATE,
                        onStart = {
                            leaveShadeOpen = statusBarStateController.leaveOpenOnKeyguardHide()
                            willRunDismissFromKeyguard = willRunAnimationOnKeyguard()
                            isShadeExpanded = isAnyExpanded
                        },
                        onStep = { 1f - it },
                    )
                    .map {
                        if (willRunDismissFromKeyguard) {
                            if (isShadeExpanded) {
                                ScrimAlpha(
                                    behindAlpha = it,
                                    notificationsAlpha = it,
                                )
                            } else {
                                ScrimAlpha()
                            }
                        } else if (leaveShadeOpen) {
                            ScrimAlpha(
                                behindAlpha = 1f,
                                notificationsAlpha = 1f,
                            )
                        } else {
                            ScrimAlpha(behindAlpha = it)
                        }
                    }
            }
    }
}
