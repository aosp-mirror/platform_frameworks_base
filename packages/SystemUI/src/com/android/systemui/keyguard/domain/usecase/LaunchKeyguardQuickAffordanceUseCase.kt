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
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.StrongAuthFlags
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

/** Defines interface for classes that can launch a quick affordance. */
interface LaunchKeyguardQuickAffordanceUseCase {
    operator fun invoke(
        intent: Intent,
        canShowWhileLocked: Boolean,
        animationController: ActivityLaunchAnimator.Controller?,
    )
}

/** Real implementation of [LaunchKeyguardQuickAffordanceUseCase] */
class LaunchKeyguardQuickAffordanceUseCaseImpl
@Inject
constructor(
    private val lockPatternUtils: LockPatternUtils,
    private val keyguardStateController: KeyguardStateController,
    private val userTracker: UserTracker,
    private val activityStarter: ActivityStarter,
) : LaunchKeyguardQuickAffordanceUseCase {
    override operator fun invoke(
        intent: Intent,
        canShowWhileLocked: Boolean,
        animationController: ActivityLaunchAnimator.Controller?,
    ) {
        @StrongAuthFlags
        val strongAuthFlags =
            lockPatternUtils.getStrongAuthForUser(userTracker.userHandle.identifier)
        val needsToUnlockFirst =
            when {
                strongAuthFlags ==
                    LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT -> true
                !canShowWhileLocked && !keyguardStateController.isUnlocked -> true
                else -> false
            }
        if (needsToUnlockFirst) {
            activityStarter.postStartActivityDismissingKeyguard(
                intent,
                0 /* delay */,
                animationController
            )
        } else {
            activityStarter.startActivity(
                intent,
                true /* dismissShade */,
                animationController,
                true /* showOverLockscreenWhenLocked */,
            )
        }
    }
}
