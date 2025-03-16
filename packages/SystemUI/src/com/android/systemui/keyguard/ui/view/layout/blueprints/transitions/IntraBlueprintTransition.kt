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

package com.android.systemui.keyguard.ui.view.layout.blueprints.transitions

import android.transition.TransitionSet
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.ClockSizeTransition
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.DefaultClockSteppingTransition
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel

class IntraBlueprintTransition(
    config: IntraBlueprintTransition.Config,
    clockViewModel: KeyguardClockViewModel,
    smartspaceViewModel: KeyguardSmartspaceViewModel,
) : TransitionSet() {

    enum class Type(val priority: Int, val animateNotifChanges: Boolean) {
        ClockSize(100, true),
        ClockCenter(99, false),
        DefaultClockStepping(98, false),
        SmartspaceVisibility(3, true),
        DefaultTransition(2, false),
        // When transition between blueprint, we don't need any duration or interpolator but we need
        // all elements go to correct state
        NoTransition(1, false),
        // Similar to NoTransition, except also does not explicitly update any alpha. Used in
        // OFF->LOCKSCREEN transition
        Init(0, false),
    }

    data class Config(
        val type: Type,
        val checkPriority: Boolean = true,
        val terminatePrevious: Boolean = true,
        val rebuildSections: List<KeyguardSection> = listOf(),
    ) {
        companion object {
            val DEFAULT = Config(Type.NoTransition)
        }
    }

    init {
        ordering = ORDERING_TOGETHER
        when (config.type) {
            Type.Init -> {}
            Type.NoTransition -> {}
            Type.DefaultClockStepping ->
                addTransition(
                    clockViewModel.currentClock.value?.let { DefaultClockSteppingTransition(it) }
                )
            else -> addTransition(ClockSizeTransition(config, clockViewModel))
        }
    }
}
