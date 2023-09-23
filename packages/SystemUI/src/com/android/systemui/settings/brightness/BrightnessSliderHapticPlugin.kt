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
import com.android.systemui.haptics.slider.SeekableSliderEventProducer

/** Plugin component for the System UI brightness slider to incorporate dynamic haptics */
interface BrightnessSliderHapticPlugin {

    /** Finger velocity tracker */
    val velocityTracker: VelocityTracker?
        get() = null

    /** Producer of slider events from the underlying [android.widget.SeekBar] */
    val seekableSliderEventProducer: SeekableSliderEventProducer?
        get() = null

    /**
     * Start the plugin.
     *
     * This starts the tracking of slider states, events and triggering of haptic feedback.
     */
    fun start() {}

    /**
     * Stop the plugin
     *
     * This stops the tracking of slider states, events and triggers of haptic feedback.
     */
    fun stop() {}
}
