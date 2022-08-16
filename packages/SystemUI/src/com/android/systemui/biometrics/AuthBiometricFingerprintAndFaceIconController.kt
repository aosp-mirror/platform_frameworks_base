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
import android.widget.ImageView
import com.android.systemui.R
import com.android.systemui.biometrics.AuthBiometricView.BiometricState
import com.android.systemui.biometrics.AuthBiometricView.STATE_PENDING_CONFIRMATION
import com.android.systemui.biometrics.AuthBiometricView.STATE_AUTHENTICATED
import com.android.systemui.biometrics.AuthBiometricView.STATE_ERROR
import com.android.systemui.biometrics.AuthBiometricView.STATE_HELP

/** Face/Fingerprint combined icon animator for BiometricPrompt. */
class AuthBiometricFingerprintAndFaceIconController(
    context: Context,
    iconView: ImageView
) : AuthBiometricFingerprintIconController(context, iconView) {

    override val actsAsConfirmButton: Boolean = true

    override fun shouldAnimateForTransition(
        @BiometricState oldState: Int,
        @BiometricState newState: Int
    ): Boolean = when (newState) {
        STATE_PENDING_CONFIRMATION -> true
        STATE_AUTHENTICATED -> false
        else -> super.shouldAnimateForTransition(oldState, newState)
    }

    override fun getAnimationForTransition(
        @BiometricState oldState: Int,
        @BiometricState newState: Int
    ): Drawable? = when (newState) {
        STATE_PENDING_CONFIRMATION -> {
            if (oldState == STATE_ERROR || oldState == STATE_HELP) {
                context.getDrawable(R.drawable.fingerprint_dialog_error_to_unlock)
            } else {
                context.getDrawable(R.drawable.fingerprint_dialog_fp_to_unlock)
            }
        }
        STATE_AUTHENTICATED -> null
        else -> super.getAnimationForTransition(oldState, newState)
    }
}
