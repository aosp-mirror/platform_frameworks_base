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

import com.android.systemui.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromPrimaryBouncerTransitionInteractor.Companion.TO_GONE_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.keyguard.shared.model.ScrimAlpha
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.statusbar.SysuiStatusBarStateController
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Breaks down PRIMARY_BOUNCER->GONE transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class PrimaryBouncerToGoneTransitionViewModel
@Inject
constructor(
    private val interactor: KeyguardTransitionInteractor,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
) {
    private val transitionAnimation =
        KeyguardTransitionAnimationFlow(
            transitionDuration = TO_GONE_DURATION,
            transitionFlow = interactor.primaryBouncerToGoneTransition,
        )

    private var leaveShadeOpen: Boolean = false
    private var willRunDismissFromKeyguard: Boolean = false

    /** Bouncer container alpha */
    val bouncerAlpha: Flow<Float> =
        transitionAnimation.createFlow(
            duration = 200.milliseconds,
            onStart = {
                willRunDismissFromKeyguard = primaryBouncerInteractor.willRunDismissFromKeyguard()
            },
            onStep = {
                if (willRunDismissFromKeyguard) {
                    0f
                } else {
                    1f - it
                }
            },
        )

    /** Scrim alpha values */
    val scrimAlpha: Flow<ScrimAlpha> =
        transitionAnimation
            .createFlow(
                duration = TO_GONE_DURATION,
                interpolator = EMPHASIZED_ACCELERATE,
                onStart = {
                    leaveShadeOpen = statusBarStateController.leaveOpenOnKeyguardHide()
                    willRunDismissFromKeyguard =
                        primaryBouncerInteractor.willRunDismissFromKeyguard()
                },
                onStep = { 1f - it },
            )
            .map {
                if (willRunDismissFromKeyguard) {
                    ScrimAlpha()
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
