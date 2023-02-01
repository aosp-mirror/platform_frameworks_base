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

import androidx.annotation.StringRes
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.containeddrawable.ContainedDrawable
import com.android.systemui.keyguard.domain.quickaffordance.KeyguardQuickAffordanceConfig
import kotlin.reflect.KClass

/** Models the UI state of a keyguard quick affordance button. */
data class KeyguardQuickAffordanceViewModel(
    val configKey: KClass<out KeyguardQuickAffordanceConfig>? = null,
    val isVisible: Boolean = false,
    /** Whether to animate the transition of the quick affordance from invisible to visible. */
    val animateReveal: Boolean = false,
    val icon: ContainedDrawable = ContainedDrawable.WithResource(0),
    @StringRes val contentDescriptionResourceId: Int = 0,
    val onClicked: (OnClickedParameters) -> Unit = {},
    val isClickable: Boolean = false,
) {
    data class OnClickedParameters(
        val configKey: KClass<out KeyguardQuickAffordanceConfig>,
        val animationController: ActivityLaunchAnimator.Controller?,
    )
}
