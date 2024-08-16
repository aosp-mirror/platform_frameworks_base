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
import com.android.app.animation.Interpolators.EMPHASIZED_ACCELERATE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromPrimaryBouncerTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down PRIMARY BOUNCER->LOCKSCREEN transition into discrete steps for corresponding views to
 * consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class PrimaryBouncerToLockscreenTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromPrimaryBouncerTransitionInteractor.TO_LOCKSCREEN_DURATION,
                edge = Edge.create(from = Scenes.Bouncer, to = LOCKSCREEN),
            )
            .setupWithoutSceneContainer(
                edge = Edge.create(from = PRIMARY_BOUNCER, to = LOCKSCREEN),
            )

    val shortcutsAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            interpolator = EMPHASIZED_ACCELERATE,
            onStep = { it }
        )

    fun lockscreenAlpha(viewState: ViewStateAccessor): Flow<Float> {
        var currentAlpha = 0f
        return transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            onStart = { currentAlpha = viewState.alpha() },
            onStep = { MathUtils.lerp(currentAlpha, 1f, it) },
        )
    }

    val deviceEntryBackgroundViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(1f)
    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(1f)
}
