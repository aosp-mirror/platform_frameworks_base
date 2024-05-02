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
import com.android.systemui.keyguard.domain.interactor.FromAodTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/** Breaks down AOD->GONE transition into discrete steps for corresponding views to consume. */
@ExperimentalCoroutinesApi
@SysUISingleton
class AodToGoneTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        animationFlow.setup(
            duration = FromAodTransitionInteractor.TO_GONE_DURATION,
            from = KeyguardState.AOD,
            to = KeyguardState.GONE,
        )

    /**
     * AOD -> GONE should fade out the lockscreen contents. This transition plays both during wake
     * and unlock, and also during insecure camera launch (which is GONE -> AOD (canceled) -> GONE).
     */
    fun lockscreenAlpha(viewState: ViewStateAccessor): Flow<Float> {
        var startAlpha = 1f
        return transitionAnimation.sharedFlow(
            duration = 200.milliseconds,
            onStart = { startAlpha = viewState.alpha() },
            onStep = { MathUtils.lerp(startAlpha, 0f, it) },
            onFinish = { 0f },
        )
    }

    fun notificationAlpha(viewState: ViewStateAccessor): Flow<Float> {
        var startAlpha = 1f
        return transitionAnimation.sharedFlow(
            duration = 200.milliseconds,
            onStart = { startAlpha = viewState.alpha() },
            onStep = { startAlpha },
            onFinish = { 1f },
        )
    }

    override val deviceEntryParentViewAlpha = transitionAnimation.immediatelyTransitionTo(0f)
}
