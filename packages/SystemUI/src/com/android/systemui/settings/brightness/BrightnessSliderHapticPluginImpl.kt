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

package com.android.systemui.settings.brightness

import android.view.VelocityTracker
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.haptics.slider.SeekableSliderEventProducer
import com.android.systemui.haptics.slider.SeekableSliderTracker
import com.android.systemui.haptics.slider.SliderHapticFeedbackProvider
import com.android.systemui.haptics.slider.SliderTracker
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Implementation of the [BrightnessSliderHapticPlugin].
 *
 * For the specifics of the brightness slider in System UI, a [SeekableSliderEventProducer] is used
 * as the producer of slider events, a [SliderHapticFeedbackProvider] is used as the listener of
 * slider states to play haptic feedback depending on the state, and a [SeekableSliderTracker] is
 * used as the state machine handler that tracks and manipulates the slider state.
 */
class BrightnessSliderHapticPluginImpl
@JvmOverloads
constructor(
    vibratorHelper: VibratorHelper,
    systemClock: SystemClock,
    @Main mainDispatcher: CoroutineDispatcher,
    override val velocityTracker: VelocityTracker = VelocityTracker.obtain(),
    override val seekableSliderEventProducer: SeekableSliderEventProducer =
        SeekableSliderEventProducer(),
) : BrightnessSliderHapticPlugin {

    private val sliderHapticFeedbackProvider: SliderHapticFeedbackProvider =
        SliderHapticFeedbackProvider(vibratorHelper, velocityTracker, clock = systemClock)
    private val sliderTracker: SliderTracker =
        SeekableSliderTracker(
            sliderHapticFeedbackProvider,
            seekableSliderEventProducer,
            mainDispatcher,
        )

    val isTracking: Boolean
        get() = sliderTracker.isTracking

    override fun start() {
        if (!sliderTracker.isTracking) {
            sliderTracker.startTracking()
        }
    }

    override fun stop() {
        sliderTracker.stopTracking()
    }
}
