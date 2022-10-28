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
import com.android.systemui.R
import com.android.systemui.biometrics.AuthBiometricView.BiometricState
import com.android.systemui.biometrics.AuthBiometricView.STATE_AUTHENTICATED
import com.android.systemui.biometrics.AuthBiometricView.STATE_AUTHENTICATING
import com.android.systemui.biometrics.AuthBiometricView.STATE_AUTHENTICATING_ANIMATING_IN
import com.android.systemui.biometrics.AuthBiometricView.STATE_ERROR
import com.android.systemui.biometrics.AuthBiometricView.STATE_HELP
import com.android.systemui.biometrics.AuthBiometricView.STATE_IDLE
import com.android.systemui.biometrics.AuthBiometricView.STATE_PENDING_CONFIRMATION

private const val TAG = "AuthBiometricFaceIconController"

/** Face only icon animator for BiometricPrompt. */
class AuthBiometricFaceIconController(
        context: Context,
        iconView: LottieAnimationView
) : AuthIconController(context, iconView) {

    // false = dark to light, true = light to dark
    private var lastPulseLightToDark = false

    @BiometricState
    private var state = 0

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
        if (state == STATE_AUTHENTICATING || state == STATE_HELP) {
            pulseInNextDirection()
        }
    }

    override fun updateIcon(@BiometricState oldState: Int, @BiometricState newState: Int) {
        val lastStateIsErrorIcon = (oldState == STATE_ERROR || oldState == STATE_HELP)
        if (newState == STATE_AUTHENTICATING_ANIMATING_IN) {
            showStaticDrawable(R.drawable.face_dialog_pulse_dark_to_light)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_authenticating
            )
        } else if (newState == STATE_AUTHENTICATING) {
            startPulsing()
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_authenticating
            )
        } else if (oldState == STATE_PENDING_CONFIRMATION && newState == STATE_AUTHENTICATED) {
            animateIconOnce(R.drawable.face_dialog_dark_to_checkmark)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_confirmed
            )
        } else if (lastStateIsErrorIcon && newState == STATE_IDLE) {
            animateIconOnce(R.drawable.face_dialog_error_to_idle)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_idle
            )
        } else if (lastStateIsErrorIcon && newState == STATE_AUTHENTICATED) {
            animateIconOnce(R.drawable.face_dialog_dark_to_checkmark)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated
            )
        } else if (newState == STATE_ERROR && oldState != STATE_ERROR) {
            animateIconOnce(R.drawable.face_dialog_dark_to_error)
        } else if (oldState == STATE_AUTHENTICATING && newState == STATE_AUTHENTICATED) {
            animateIconOnce(R.drawable.face_dialog_dark_to_checkmark)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated
            )
        } else if (newState == STATE_PENDING_CONFIRMATION) {
            animateIconOnce(R.drawable.face_dialog_wink_from_dark)
            iconView.contentDescription = context.getString(
                    R.string.biometric_dialog_face_icon_description_authenticated
            )
        } else if (newState == STATE_IDLE) {
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
