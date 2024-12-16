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

package com.android.systemui.keyguard.ui.viewmodel

import android.util.MathUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromDozingTransitionInteractor.Companion.TO_PRIMARY_BOUNCER_DURATION
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition.Companion.MAX_BACKGROUND_BLUR_RADIUS
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition.Companion.MIN_BACKGROUND_BLUR_RADIUS
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down DOZING->PRIMARY BOUNCER transition into discrete steps for corresponding views to
 * consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class DozingToPrimaryBouncerTransitionViewModel
@Inject
constructor(animationFlow: KeyguardTransitionAnimationFlow) :
    DeviceEntryIconTransition, PrimaryBouncerTransition {

    private val transitionAnimation =
        animationFlow
            .setup(
                duration = TO_PRIMARY_BOUNCER_DURATION,
                edge = Edge.create(from = DOZING, to = Scenes.Bouncer),
            )
            .setupWithoutSceneContainer(edge = Edge.create(from = DOZING, to = PRIMARY_BOUNCER))

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0f)

    override val windowBlurRadius: Flow<Float> =
        transitionAnimation.sharedFlow(
            TO_PRIMARY_BOUNCER_DURATION,
            onStep = { step ->
                MathUtils.lerp(MIN_BACKGROUND_BLUR_RADIUS, MAX_BACKGROUND_BLUR_RADIUS, step)
            },
            onFinish = { MAX_BACKGROUND_BLUR_RADIUS },
        )
}
