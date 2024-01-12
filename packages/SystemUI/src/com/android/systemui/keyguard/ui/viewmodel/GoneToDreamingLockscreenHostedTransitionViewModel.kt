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
import com.android.systemui.keyguard.domain.interactor.FromGoneTransitionInteractor.Companion.TO_DREAMING_DURATION
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down GONE->DREAMING_LOCKSCREEN_HOSTED transition into discrete steps for corresponding
 * views to consume.
 */
@SysUISingleton
class GoneToDreamingLockscreenHostedTransitionViewModel
@Inject
constructor(
    animationFlow: KeyguardTransitionAnimationFlow,
) {

    private val transitionAnimation =
        animationFlow.setup(
            duration = TO_DREAMING_DURATION,
            from = KeyguardState.GONE,
            to = KeyguardState.DREAMING_LOCKSCREEN_HOSTED,
        )

    /** Lockscreen views alpha - hide immediately */
    val lockscreenAlpha: Flow<Float> =
        transitionAnimation.sharedFlow(
            duration = 1.milliseconds,
            onStep = { 0f },
        )
}
