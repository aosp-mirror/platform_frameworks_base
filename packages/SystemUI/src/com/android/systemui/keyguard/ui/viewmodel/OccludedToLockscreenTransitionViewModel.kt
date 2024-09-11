/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.app.animation.Interpolators.EMPHASIZED_DECELERATE
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.FromOccludedTransitionInteractor.Companion.TO_LOCKSCREEN_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.pairwise
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Breaks down OCCLUDED->LOCKSCREEN transition into discrete steps for corresponding views to
 * consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class OccludedToLockscreenTransitionViewModel
@Inject
constructor(
    deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
    configurationInteractor: ConfigurationInteractor,
    animationFlow: KeyguardTransitionAnimationFlow,
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        animationFlow.setup(
            duration = TO_LOCKSCREEN_DURATION,
            edge = Edge.create(from = OCCLUDED, to = LOCKSCREEN),
        )

    /** Lockscreen views y-translation */
    val lockscreenTranslationY: Flow<Float> =
        configurationInteractor
            .dimensionPixelSize(R.dimen.occluded_to_lockscreen_transition_lockscreen_translation_y)
            .flatMapLatest { translatePx ->
                transitionAnimation.sharedFlow(
                    duration = TO_LOCKSCREEN_DURATION,
                    onStep = { value -> -translatePx + value * translatePx },
                    interpolator = EMPHASIZED_DECELERATE,
                    onCancel = { 0f },
                )
            }

    val shortcutsAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 250.milliseconds,
            onStep = { it },
            onCancel = { 0f },
        )

    /** Lockscreen views alpha */
    val lockscreenAlpha: Flow<Float> =
        merge(
            transitionAnimation.sharedFlow(
                startTime = 233.milliseconds,
                duration = 250.milliseconds,
                onStep = { it },
                name = "OCCLUDED->LOCKSCREEN: lockscreenAlpha",
            ),
            // Required to fix a bug where the shade expands while lockscreenAlpha=1f, due to a call
            // to setOccluded(false) triggering a reset() call in KeyguardViewMediator. The
            // permanent solution is to only expand the shade once the keyguard transition from
            // OCCLUDED starts, but that requires more refactoring of expansion amounts. For now,
            // emit alpha = 0f for OCCLUDED -> LOCKSCREEN whenever isOccluded flips from true to
            // false while currentState == OCCLUDED, so that alpha = 0f when that expansion occurs.
            // TODO(b/332946323): Remove this once it's no longer needed.
            keyguardInteractor.isKeyguardOccluded
                .pairwise()
                .filter { (wasOccluded, isOccluded) ->
                    wasOccluded &&
                        !isOccluded &&
                        keyguardTransitionInteractor.getCurrentState() == OCCLUDED
                }
                .map { 0f }
        )

    val deviceEntryBackgroundViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(1f)

    override val deviceEntryParentViewAlpha: Flow<Float> = lockscreenAlpha
}
