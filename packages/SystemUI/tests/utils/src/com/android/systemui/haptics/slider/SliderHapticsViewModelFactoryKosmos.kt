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

package com.android.systemui.haptics.slider

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.InteractionSource
import com.android.systemui.haptics.msdl.msdlPlayer
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.haptics.vibratorHelper
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.util.time.fakeSystemClock

val Kosmos.sliderHapticsViewModelFactory by
    Kosmos.Fixture {
        object : SliderHapticsViewModel.Factory {
            override fun create(
                interactionSource: InteractionSource,
                sliderRange: ClosedFloatingPointRange<Float>,
                orientation: Orientation,
                sliderHapticFeedbackConfig: SliderHapticFeedbackConfig,
                sliderTrackerConfig: SeekableSliderTrackerConfig,
            ): SliderHapticsViewModel =
                SliderHapticsViewModel(
                    interactionSource,
                    sliderRange,
                    orientation,
                    sliderHapticFeedbackConfig,
                    sliderTrackerConfig,
                    vibratorHelper,
                    msdlPlayer,
                    fakeSystemClock,
                )
        }
    }
