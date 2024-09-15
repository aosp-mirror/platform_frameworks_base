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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor
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
 * Breaks down LOCKSCREEN->PRIMARY BOUNCER transition into discrete steps for corresponding views to
 * consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class LockscreenToPrimaryBouncerTransitionViewModel
@Inject
constructor(
    shadeDependentFlows: ShadeDependentFlows,
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromLockscreenTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
                edge = Edge.create(from = LOCKSCREEN, to = Scenes.Bouncer),
            )
            .setupWithoutSceneContainer(
                edge = Edge.create(from = LOCKSCREEN, to = PRIMARY_BOUNCER),
            )

    val shortcutsAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = FromLockscreenTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
            onStep = { 1f - it }
        )

    val lockscreenAlpha: Flow<Float> = shortcutsAlpha

    override val deviceEntryParentViewAlpha: Flow<Float> =
        shadeDependentFlows.transitionFlow(
            flowWhenShadeIsNotExpanded =
                transitionAnimation.sharedFlow(
                    duration = 250.milliseconds,
                    onStep = { 1f - it },
                    onFinish = { 0f }
                ),
            flowWhenShadeIsExpanded = transitionAnimation.immediatelyTransitionTo(0f)
        )
}
