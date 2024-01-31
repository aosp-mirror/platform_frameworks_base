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
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.ClockSizeTransition
import com.android.systemui.keyguard.ui.view.layout.sections.transitions.DefaultClockSteppingTransition
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel

enum class IntraBlueprintTransitionType {
    ClockSize,
    ClockCenter,
    DefaultClockStepping,
    DefaultTransition,
    AodNotifIconsTransition,
    // When transition between blueprint, we don't need any duration or interpolator but we need
    // all elements go to correct state
    NoTransition,
}

class IntraBlueprintTransition(
    type: IntraBlueprintTransitionType,
    clockViewModel: KeyguardClockViewModel
) : TransitionSet() {
    init {
        ordering = ORDERING_TOGETHER
        if (type == IntraBlueprintTransitionType.DefaultClockStepping)
            addTransition(clockViewModel.clock?.let { DefaultClockSteppingTransition(it) })
        addTransition(ClockSizeTransition(type, clockViewModel))
    }
}
