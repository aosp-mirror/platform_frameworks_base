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

import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.keyguard.domain.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.domain.quickaffordance.KeyguardQuickAffordanceConfig.OnClickedResult
import com.android.systemui.keyguard.domain.quickaffordance.KeyguardQuickAffordanceRegistry
import javax.inject.Inject
import kotlin.reflect.KClass

/** Use-case for handling a click on a keyguard quick affordance (e.g. bottom button). */
class OnKeyguardQuickAffordanceClickedUseCase
@Inject
constructor(
    private val registry: KeyguardQuickAffordanceRegistry,
    private val launchAffordanceUseCase: LaunchKeyguardQuickAffordanceUseCase,
) {
    operator fun invoke(
        configKey: KClass<*>,
        animationController: ActivityLaunchAnimator.Controller?,
    ) {
        @Suppress("UNCHECKED_CAST")
        val config = registry.get(configKey as KClass<out KeyguardQuickAffordanceConfig>)
        when (val result = config.onQuickAffordanceClicked(animationController)) {
            is OnClickedResult.StartActivity ->
                launchAffordanceUseCase(
                    intent = result.intent,
                    canShowWhileLocked = result.canShowWhileLocked,
                    animationController = animationController
                )
            is OnClickedResult.Handled -> Unit
        }
    }
}
