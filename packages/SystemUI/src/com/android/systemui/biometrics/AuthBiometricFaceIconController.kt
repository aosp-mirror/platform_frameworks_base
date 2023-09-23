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

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import com.airbnb.lottie.LottieAnimationView
import com.android.systemui.res.R
import com.android.systemui.biometrics.ui.binder.Spaghetti.BiometricState

private const val TAG = "AuthBiometricFaceIconController"

/** Face only icon animator for BiometricPrompt. */
class AuthBiometricFaceIconController(
        context: Context,
        iconView: LottieAnimationView
) : AuthIconController(context, iconView) {

    // false = dark to light, true = light to dark
    private var lastPulseLightToDark = false

    private var state: BiometricState = BiometricState.STATE_IDLE

    init {
        val size = context.resources.getDimensionPixelSize(R.dimen.biometric_dialog_face_icon_size)
        iconView.layoutParams.width = size
        iconView.layoutParams.height = size
        showStaticDrawable(R.drawable.face_dialog_pulse_dark_to_light)
    }

    private fun startPulsing() {
        lastPulseLightToDark = false
        animateIcon(R.drawable.face_dialog_pulse_dark_to_light, true)
    }

    private fun pulseInNextDirection() {
        val iconRes = if (lastPulseLightToDark) {
            R.drawable.face_dialog_pulse_dark_to_light
        } else {
            R.drawable.face_dialog_pulse_light_to_dark
        }
        animateIcon(iconRes, true /* repeat */)
        lastPulseLightToDark = !lastPulseLightToDark
    }

    override fun handleAnimationEnd(drawable: Drawable) {
        if (state == BiometricState.STATE_AUTHENTICATING || state == BiometricState.STATE_HELP) {
            pulseInNextDirection()
        }
    }

    override fun updateIcon(oldState: BiometricState, newState: BiometricState) {
        val lastStateIsErrorIcon = (oldState == BiometricState.STATE_ERROR || oldState == BiometricState.STATE_HELP)
        if (newState == BiometricState.STATE_AUTHENTICATING_ANIMATING_IN) {
            showStaticDrawable(R.drawable.face_dialog_pulse_dark_to_light)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_authenticating
            )
        } else if (newState == BiometricState.STATE_AUTHENTICATING) {
            startPulsing()
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_authenticating
            )
        } else if (oldState == BiometricState.STATE_PENDING_CONFIRMATION && newState == BiometricState.STATE_AUTHENTICATED) {
            animateIconOnce(R.drawable.face_dialog_dark_to_checkmark)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_confirmed
            )
        } else if (lastStateIsErrorIcon && newState == BiometricState.STATE_IDLE) {
            animateIconOnce(R.drawable.face_dialog_error_to_idle)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_idle
            )
        } else if (lastStateIsErrorIcon && newState == BiometricState.STATE_AUTHENTICATED) {
            animateIconOnce(R.drawable.face_dialog_dark_to_checkmark)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated
            )
        } else if (newState == BiometricState.STATE_ERROR && oldState != BiometricState.STATE_ERROR) {
            animateIconOnce(R.drawable.face_dialog_dark_to_error)
            iconView.contentDescription = context.getString(
                    R.string.keyguard_face_failed
            )
        } else if (oldState == BiometricState.STATE_AUTHENTICATING && newState == BiometricState.STATE_AUTHENTICATED) {
            animateIconOnce(R.drawable.face_dialog_dark_to_checkmark)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated
            )
        } else if (newState == BiometricState.STATE_PENDING_CONFIRMATION) {
            animateIconOnce(R.drawable.face_dialog_wink_from_dark)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated
            )
        } else if (newState == BiometricState.STATE_IDLE) {
            showStaticDrawable(R.drawable.face_dialog_idle_static)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_idle
            )
        } else {
            Log.w(TAG, "Unhandled state: $newState")
        }
        state = newState
    }
}
