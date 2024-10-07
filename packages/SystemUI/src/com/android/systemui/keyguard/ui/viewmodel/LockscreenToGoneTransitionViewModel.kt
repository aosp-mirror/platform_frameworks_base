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
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow.FlowBuilder
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.SysuiStatusBarStateController
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down LOCKSCREEN->GONE transition into discrete steps for corresponding views to consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class LockscreenToGoneTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
    private val statusBarStateController: SysuiStatusBarStateController,
) : DeviceEntryIconTransition {

    private val transitionAnimation: FlowBuilder =
        animationFlow
            .setup(
                duration = FromLockscreenTransitionInteractor.TO_GONE_DURATION,
                edge = Edge.create(from = LOCKSCREEN, to = Scenes.Gone),
            )
            .setupWithoutSceneContainer(
                edge = Edge.create(from = LOCKSCREEN, to = GONE),
            )

    val shortcutsAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 200.milliseconds,
            onStep = { 1 - it },
            onFinish = { 0f },
            onCancel = { 1f },
        )

    fun notificationAlpha(viewState: ViewStateAccessor): Flow<Float> {
        var startAlpha = 1f
        var leaveShadeOpen = false

        return transitionAnimation.sharedFlow(
            duration = 80.milliseconds,
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
        )
    }

    fun lockscreenAlpha(viewState: ViewStateAccessor): Flow<Float> {
        var startAlpha = 1f
        return transitionAnimation.sharedFlow(
            duration = 200.milliseconds,
            onStart = { startAlpha = viewState.alpha() },
            onStep = { MathUtils.lerp(startAlpha, 0f, it) },
            onFinish = { 0f },
            onCancel = { 1f },
        )
    }

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0f)
}
