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

package com.android.systemui.biometrics

import android.annotation.DrawableRes
import android.content.Context
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.airbnb.lottie.LottieAnimationView
import com.android.systemui.biometrics.AuthBiometricView.BiometricState

private const val TAG = "AuthIconController"

/** Controller for animating the BiometricPrompt icon/affordance. */
abstract class AuthIconController(
        protected val context: Context,
        protected val iconView: LottieAnimationView
) : Animatable2.AnimationCallback() {

    /** If this controller should ignore events and pause. */
    var deactivated: Boolean = false

    /** If the icon view should be treated as an alternate "confirm" button. */
    open val actsAsConfirmButton: Boolean = false

    final override fun onAnimationStart(drawable: Drawable) {
        super.onAnimationStart(drawable)
    }

    final override fun onAnimationEnd(drawable: Drawable) {
        super.onAnimationEnd(drawable)

        if (!deactivated) {
            handleAnimationEnd(drawable)
        }
    }

    /** Set the icon to a static image. */
    protected fun showStaticDrawable(@DrawableRes iconRes: Int) {
        iconView.setImageDrawable(context.getDrawable(iconRes))
    }

    /** Animate a resource. */
    protected fun animateIconOnce(@DrawableRes iconRes: Int) {
        animateIcon(iconRes, false)
    }

    /** Animate a resource. */
    protected fun animateIcon(@DrawableRes iconRes: Int, repeat: Boolean) {
        if (!deactivated) {
            val icon = context.getDrawable(iconRes) as AnimatedVectorDrawable
            iconView.setImageDrawable(icon)
            icon.forceAnimationOnUI()
            if (repeat) {
                icon.registerAnimationCallback(this)
            }
            icon.start()
        }
    }

    /** Update the icon to reflect the [newState]. */
    fun updateState(@BiometricState lastState: Int, @BiometricState newState: Int) {
        if (deactivated) {
            Log.w(TAG, "Ignoring updateState when deactivated: $newState")
        } else {
            updateIcon(lastState, newState)
        }
    }

    /** If the icon should act as a "retry" button in the [currentState]. */
    fun iconTapSendsRetryWhen(@BiometricState currentState: Int): Boolean = false

    /** Call during [updateState] if the controller is not [deactivated]. */
    abstract fun updateIcon(@BiometricState lastState: Int, @BiometricState newState: Int)

    /** Called during [onAnimationEnd] if the controller is not [deactivated]. */
    open fun handleAnimationEnd(drawable: Drawable) {}
}
