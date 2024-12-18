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

import android.util.MathUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromAlternateBouncerTransitionInteractor.Companion.TO_GONE_DURATION
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.ScrimAlpha
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.SysuiStatusBarStateController
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down ALTERNATE_BOUNCER->GONE transition into discrete steps for corresponding views to
 * consume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class AlternateBouncerToGoneTransitionViewModel
@Inject
constructor(
    bouncerToGoneFlows: BouncerToGoneFlows,
    animationFlow: KeyguardTransitionAnimationFlow,
    private val statusBarStateController: SysuiStatusBarStateController,
) : DeviceEntryIconTransition {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = TO_GONE_DURATION,
                edge = Edge.create(from = ALTERNATE_BOUNCER, to = Scenes.Gone),
            )
            .setupWithoutSceneContainer(
                edge = Edge.create(from = ALTERNATE_BOUNCER, to = GONE),
            )

    fun lockscreenAlpha(viewState: ViewStateAccessor): Flow<Float> {
        var startAlpha = 1f
        return transitionAnimation.sharedFlow(
            duration = 200.milliseconds,
            onStart = { startAlpha = viewState.alpha() },
            onStep = { MathUtils.lerp(startAlpha, 0f, it) },
            onFinish = { 0f },
            onCancel = { startAlpha },
        )
    }

    fun notificationAlpha(viewState: ViewStateAccessor): Flow<Float> {
        var startAlpha = 1f
        var leaveShadeOpen = false

        return transitionAnimation.sharedFlow(
            duration = 200.milliseconds,
            onStart = {
                leaveShadeOpen = statusBarStateController.leaveOpenOnKeyguardHide()
                startAlpha = viewState.alpha()
            },
            onStep = {
                if (leaveShadeOpen) {
                    1f
                } else {
                    MathUtils.lerp(startAlpha, 0f, it)
                }
            },
            onFinish = { 1f },
        )
    }

    /** See [BouncerToGoneFlows#showAllNotifications] */
    val showAllNotifications: Flow<Boolean> =
        bouncerToGoneFlows.showAllNotifications(TO_GONE_DURATION, ALTERNATE_BOUNCER)

    /** Scrim alpha values */
    val scrimAlpha: Flow<ScrimAlpha> =
        bouncerToGoneFlows.scrimAlpha(TO_GONE_DURATION, ALTERNATE_BOUNCER)

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0f)
}
