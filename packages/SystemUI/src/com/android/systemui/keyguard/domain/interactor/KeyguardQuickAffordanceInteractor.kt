/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import android.content.Intent
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordancePosition
import com.android.systemui.keyguard.domain.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.domain.quickaffordance.KeyguardQuickAffordanceRegistry
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

@SysUISingleton
class KeyguardQuickAffordanceInteractor
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
    private val registry: KeyguardQuickAffordanceRegistry<out KeyguardQuickAffordanceConfig>,
    private val lockPatternUtils: LockPatternUtils,
    private val keyguardStateController: KeyguardStateController,
    private val userTracker: UserTracker,
    private val activityStarter: ActivityStarter,
) {
    /** Returns an observable for the quick affordance at the given position. */
    fun quickAffordance(
        position: KeyguardQuickAffordancePosition
    ): Flow<KeyguardQuickAffordanceModel> {
        return combine(
            quickAffordanceInternal(position),
            keyguardInteractor.isDozing,
            keyguardInteractor.isKeyguardShowing,
        ) { affordance, isDozing, isKeyguardShowing ->
            if (!isDozing && isKeyguardShowing) {
                affordance
            } else {
                KeyguardQuickAffordanceModel.Hidden
            }
        }
    }

    /**
     * Notifies that a quick affordance has been clicked by the user.
     *
     * @param configKey The configuration key corresponding to the [KeyguardQuickAffordanceModel] of
     * the affordance that was clicked
     * @param animationController An optional controller for the activity-launch animation
     */
    fun onQuickAffordanceClicked(
        configKey: KClass<out KeyguardQuickAffordanceConfig>,
        animationController: ActivityLaunchAnimator.Controller?,
    ) {
        @Suppress("UNCHECKED_CAST") val config = registry.get(configKey as KClass<Nothing>)
        when (val result = config.onQuickAffordanceClicked(animationController)) {
            is KeyguardQuickAffordanceConfig.OnClickedResult.StartActivity ->
                launchQuickAffordance(
                    intent = result.intent,
                    canShowWhileLocked = result.canShowWhileLocked,
                    animationController = animationController
                )
            is KeyguardQuickAffordanceConfig.OnClickedResult.Handled -> Unit
        }
    }

    private fun quickAffordanceInternal(
        position: KeyguardQuickAffordancePosition
    ): Flow<KeyguardQuickAffordanceModel> {
        val configs = registry.getAll(position)
        return combine(
            configs.map { config ->
                // We emit an initial "Hidden" value to make sure that there's always an initial
                // value and avoid subtle bugs where the downstream isn't receiving any values
                // because one config implementation is not emitting an initial value. For example,
                // see b/244296596.
                config.state.onStart { emit(KeyguardQuickAffordanceConfig.State.Hidden) }
            }
        ) { states ->
            val index = states.indexOfFirst { it is KeyguardQuickAffordanceConfig.State.Visible }
            if (index != -1) {
                val visibleState = states[index] as KeyguardQuickAffordanceConfig.State.Visible
                KeyguardQuickAffordanceModel.Visible(
                    configKey = configs[index]::class,
                    icon = visibleState.icon,
                    contentDescriptionResourceId = visibleState.contentDescriptionResourceId,
                )
            } else {
                KeyguardQuickAffordanceModel.Hidden
            }
        }
    }

    private fun launchQuickAffordance(
        intent: Intent,
        canShowWhileLocked: Boolean,
        animationController: ActivityLaunchAnimator.Controller?,
    ) {
        @LockPatternUtils.StrongAuthTracker.StrongAuthFlags
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
