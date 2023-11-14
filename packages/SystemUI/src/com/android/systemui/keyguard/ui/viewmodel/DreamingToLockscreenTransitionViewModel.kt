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

import com.android.app.animation.Interpolators.EMPHASIZED
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.FromDreamingTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.FromDreamingTransitionInteractor.Companion.TO_LOCKSCREEN_DURATION
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Breaks down DREAMING->LOCKSCREEN transition into discrete steps for corresponding views to
 * consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class DreamingToLockscreenTransitionViewModel
@Inject
constructor(
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val fromDreamingTransitionInteractor: FromDreamingTransitionInteractor,
    private val deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor,
) : DeviceEntryIconTransition {
    fun startTransition() = fromDreamingTransitionInteractor.startToLockscreenTransition()

    private val transitionAnimation =
        KeyguardTransitionAnimationFlow(
            transitionDuration = TO_LOCKSCREEN_DURATION,
            transitionFlow = keyguardTransitionInteractor.dreamingToLockscreenTransition,
        )

    val transitionEnded =
        keyguardTransitionInteractor.fromDreamingTransition.filter { step ->
            step.transitionState == TransitionState.FINISHED ||
                step.transitionState == TransitionState.CANCELED
        }

    /** Dream overlay y-translation on exit */
    fun dreamOverlayTranslationY(translatePx: Int): Flow<Float> {
        return transitionAnimation.createFlow(
            duration = TO_LOCKSCREEN_DURATION,
            onStep = { it * translatePx },
            interpolator = EMPHASIZED,
        )
    }

    /** Dream overlay views alpha - fade out */
    val dreamOverlayAlpha: Flow<Float> =
        transitionAnimation.createFlow(
            duration = 250.milliseconds,
            onStep = { 1f - it },
        )

    /** Lockscreen views y-translation */
    fun lockscreenTranslationY(translatePx: Int): Flow<Float> {
        return transitionAnimation.createFlow(
            duration = TO_LOCKSCREEN_DURATION,
            onStep = { value -> -translatePx + value * translatePx },
            // Reset on cancel or finish
            onFinish = { 0f },
            onCancel = { 0f },
            interpolator = EMPHASIZED,
        )
    }

    /** Lockscreen views alpha */
    val lockscreenAlpha: Flow<Float> =
        transitionAnimation.createFlow(
            startTime = 233.milliseconds,
            duration = 250.milliseconds,
            onStep = { it },
        )

    val deviceEntryBackgroundViewAlpha =
        deviceEntryUdfpsInteractor.isUdfpsSupported.flatMapLatest { isUdfps ->
            if (isUdfps) {
                // immediately show; will fade in with deviceEntryParentViewAlpha
                transitionAnimation.immediatelyTransitionTo(1f)
            } else {
                emptyFlow()
            }
        }
    override val deviceEntryParentViewAlpha = lockscreenAlpha
}
