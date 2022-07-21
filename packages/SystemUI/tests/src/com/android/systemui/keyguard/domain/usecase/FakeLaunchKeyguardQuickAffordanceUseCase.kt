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

package com.android.systemui.keyguard.domain.usecase

import android.content.Intent
import com.android.systemui.animation.ActivityLaunchAnimator

/** Fake implementation of [LaunchKeyguardQuickAffordanceUseCase], for tests. */
class FakeLaunchKeyguardQuickAffordanceUseCase : LaunchKeyguardQuickAffordanceUseCase {

    data class Invocation(
        val intent: Intent,
        val canShowWhileLocked: Boolean,
        val animationController: ActivityLaunchAnimator.Controller?
    )

    private val _invocations = mutableListOf<Invocation>()
    val invocations: List<Invocation> = _invocations

    override fun invoke(
        intent: Intent,
        canShowWhileLocked: Boolean,
        animationController: ActivityLaunchAnimator.Controller?
    ) {
        _invocations.add(
            Invocation(
                intent = intent,
                canShowWhileLocked = canShowWhileLocked,
                animationController = animationController,
            )
        )
    }
}
