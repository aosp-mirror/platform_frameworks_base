/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.plugins.clocks

import android.view.View
import com.android.systemui.plugins.annotations.ProtectedInterface

/** Methods which trigger various clock animations */
@ProtectedInterface
interface ClockAnimations {
    /** Runs an enter animation (if any) */
    fun enter()

    /** Sets how far into AOD the device currently is. */
    fun doze(fraction: Float)

    /** Sets how far into the folding animation the device is. */
    fun fold(fraction: Float)

    /** Runs the battery animation (if any). */
    fun charge()

    /**
     * Runs when the clock's position changed during the move animation.
     *
     * @param fromLeft the [View.getLeft] position of the clock, before it started moving.
     * @param direction the direction in which it is moving. A positive number means right, and
     *   negative means left.
     * @param fraction fraction of the clock movement. 0 means it is at the beginning, and 1 means
     *   it finished moving.
     * @deprecated use {@link #onPositionUpdated(float, float)} instead.
     */
    fun onPositionUpdated(fromLeft: Int, direction: Int, fraction: Float)

    /**
     * Runs when the clock's position changed during the move animation.
     *
     * @param distance is the total distance in pixels to offset the glyphs when animation
     *   completes. Negative distance means we are animating the position towards the center.
     * @param fraction fraction of the clock movement. 0 means it is at the beginning, and 1 means
     *   it finished moving.
     */
    fun onPositionUpdated(distance: Float, fraction: Float)

    /**
     * Runs when swiping clock picker, swipingFraction: 1.0 -> clock is scaled up in the preview,
     * 0.0 -> clock is scaled down in the shade; previewRatio is previewSize / screenSize
     */
    fun onPickerCarouselSwiping(swipingFraction: Float)
}
