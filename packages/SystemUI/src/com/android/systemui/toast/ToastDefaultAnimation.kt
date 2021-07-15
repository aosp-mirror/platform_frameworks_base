/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.toast

import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.animation.AnimatorSet

class ToastDefaultAnimation {
    /**
     * sum of the in and out animation durations cannot exceed
     * [com.android.server.policy.PhoneWindowManager.TOAST_WINDOW_ANIM_BUFFER] to prevent the toast
     * window from being removed before animations are completed
     */
    companion object {
        // total duration shouldn't exceed NotificationManagerService's delay for "in" animation
        fun toastIn(view: View): AnimatorSet? {
            val icon: View? = view.findViewById(com.android.systemui.R.id.icon)
            val text: View? = view.findViewById(com.android.systemui.R.id.text)
            if (icon == null || text == null) {
                return null
            }
            val linearInterp = LinearInterpolator()
            val scaleInterp = PathInterpolator(0f, 0f, 0f, 1f)
            val sX = ObjectAnimator.ofFloat(view, "scaleX", 0.9f, 1f).apply {
                interpolator = scaleInterp
                duration = 333
            }
            val sY = ObjectAnimator.ofFloat(view, "scaleY", 0.9f, 1f).apply {
                interpolator = scaleInterp
                duration = 333
            }
            val vA = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f).apply {
                interpolator = linearInterp
                duration = 66
            }
            text.alpha = 0f // Set now otherwise won't apply until start delay
            val tA = ObjectAnimator.ofFloat(text, "alpha", 0f, 1f).apply {
                interpolator = linearInterp
                duration = 283
                startDelay = 50
            }
            icon.alpha = 0f // Set now otherwise won't apply until start delay
            val iA = ObjectAnimator.ofFloat(icon, "alpha", 0f, 1f).apply {
                interpolator = linearInterp
                duration = 283
                startDelay = 50
            }
            return AnimatorSet().apply {
                playTogether(sX, sY, vA, tA, iA)
            }
        }

        fun toastOut(view: View): AnimatorSet? {
            // total duration shouldn't exceed NotificationManagerService's delay for "out" anim
            val icon: View? = view.findViewById(com.android.systemui.R.id.icon)
            val text: View? = view.findViewById(com.android.systemui.R.id.text)
            if (icon == null || text == null) {
                return null
            }
            val linearInterp = LinearInterpolator()
            val scaleInterp = PathInterpolator(0.3f, 0f, 1f, 1f)
            val viewScaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.9f).apply {
                interpolator = scaleInterp
                duration = 250
            }
            val viewScaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.9f).apply {
                interpolator = scaleInterp
                duration = 250
            }
            val viewElevation = ObjectAnimator.ofFloat(view, "elevation",
                view.elevation, 0f).apply {
                interpolator = linearInterp
                duration = 40
                startDelay = 150
            }
            val viewAlpha = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f).apply {
                interpolator = linearInterp
                duration = 100
                startDelay = 150
            }
            val textAlpha = ObjectAnimator.ofFloat(text, "alpha", 1f, 0f).apply {
                interpolator = linearInterp
                duration = 166
            }
            val iconAlpha = ObjectAnimator.ofFloat(icon, "alpha", 1f, 0f).apply {
                interpolator = linearInterp
                duration = 166
            }
            return AnimatorSet().apply {
                playTogether(
                    viewScaleX,
                    viewScaleY,
                    viewElevation,
                    viewAlpha,
                    textAlpha,
                    iconAlpha)
            }
        }
    }
}
