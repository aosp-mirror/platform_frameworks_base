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

import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromPrimaryBouncerTransitionInteractor.Companion.TO_GONE_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardDismissActionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.ScrimAlpha
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.statusbar.SysuiStatusBarStateController
import dagger.Lazy
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Breaks down PRIMARY_BOUNCER->GONE transition into discrete steps for corresponding views to
 * consume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class PrimaryBouncerToGoneTransitionViewModel
@Inject
constructor(
    private val statusBarStateController: SysuiStatusBarStateController,
    private val primaryBouncerInteractor: PrimaryBouncerInteractor,
    keyguardDismissActionInteractor: Lazy<KeyguardDismissActionInteractor>,
    bouncerToGoneFlows: BouncerToGoneFlows,
    animationFlow: KeyguardTransitionAnimationFlow,
) {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = TO_GONE_DURATION,
                edge = Edge.INVALID,
            )
            .setupWithoutSceneContainer(
                edge = Edge.create(from = PRIMARY_BOUNCER, to = GONE),
            )

    private var leaveShadeOpen: Boolean = false
    private var willRunDismissFromKeyguard: Boolean = false

    /** See [BouncerToGoneFlows#showAllNotifications] */
    val showAllNotifications: Flow<Boolean> =
        bouncerToGoneFlows.showAllNotifications(TO_GONE_DURATION, PRIMARY_BOUNCER)

    val notificationAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 200.milliseconds,
            onStart = {
                leaveShadeOpen = statusBarStateController.leaveOpenOnKeyguardHide()
                willRunDismissFromKeyguard = primaryBouncerInteractor.willRunDismissFromKeyguard()
            },
            onStep = {
                if (willRunDismissFromKeyguard || leaveShadeOpen) {
                    1f
                } else {
                    1f - it
                }
            },
            onFinish = { 1f },
        )

    /** Bouncer container alpha */
    val bouncerAlpha: Flow<Float> =
        if (ComposeBouncerFlags.isEnabled) {
            keyguardDismissActionInteractor
                .get()
                .willAnimateDismissActionOnLockscreen
                .flatMapLatest { createBouncerAlphaFlow { it } }
        } else {
            createBouncerAlphaFlow(primaryBouncerInteractor::willRunDismissFromKeyguard)
        }

    private fun createBouncerAlphaFlow(willRunAnimationOnKeyguard: () -> Boolean): Flow<Float> {
        return transitionAnimation.sharedFlow(
            duration = 200.milliseconds,
            onStart = { willRunDismissFromKeyguard = willRunAnimationOnKeyguard() },
            onStep = {
                if (willRunDismissFromKeyguard) {
                    0f
                } else {
                    1f - it
                }
            },
        )
    }

    /** Lockscreen alpha */
    val lockscreenAlpha: Flow<Float> =
        if (ComposeBouncerFlags.isEnabled) {
            keyguardDismissActionInteractor
                .get()
                .willAnimateDismissActionOnLockscreen
                .flatMapLatest { createLockscreenAlpha { it } }
        } else {
            createLockscreenAlpha(primaryBouncerInteractor::willRunDismissFromKeyguard)
        }

    private fun createLockscreenAlpha(willRunAnimationOnKeyguard: () -> Boolean): Flow<Float> {
        return transitionAnimation.sharedFlow(
            duration = 50.milliseconds,
            onStart = {
                leaveShadeOpen = statusBarStateController.leaveOpenOnKeyguardHide()
                willRunDismissFromKeyguard = willRunAnimationOnKeyguard()
            },
            onStep = {
                if (willRunDismissFromKeyguard || leaveShadeOpen) {
                    1f
                } else {
                    0f
                }
            },
            onFinish = { 0f },
        )
    }

    val scrimAlpha: Flow<ScrimAlpha> =
        bouncerToGoneFlows.scrimAlpha(TO_GONE_DURATION, PRIMARY_BOUNCER)
}
