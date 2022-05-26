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
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.android.systemui.R
import com.android.systemui.biometrics.AuthBiometricView.BiometricState
import com.android.systemui.biometrics.AuthBiometricView.STATE_AUTHENTICATED
import com.android.systemui.biometrics.AuthBiometricView.STATE_AUTHENTICATING
import com.android.systemui.biometrics.AuthBiometricView.STATE_AUTHENTICATING_ANIMATING_IN
import com.android.systemui.biometrics.AuthBiometricView.STATE_ERROR
import com.android.systemui.biometrics.AuthBiometricView.STATE_HELP
import com.android.systemui.biometrics.AuthBiometricView.STATE_IDLE
import com.android.systemui.biometrics.AuthBiometricView.STATE_PENDING_CONFIRMATION

/** Fingerprint only icon animator for BiometricPrompt.  */
open class AuthBiometricFingerprintIconController(
    context: Context,
    iconView: ImageView
) : AuthIconController(context, iconView) {

    var iconLayoutParamsSize = 0
        set(value) {
            if (field == value) {
                return
            }
            iconView.layoutParams.width = value
            iconView.layoutParams.height = value
            field = value
        }

    init {
        iconLayoutParamsSize = context.resources.getDimensionPixelSize(
            R.dimen.biometric_dialog_fingerprint_icon_size
        )
    }

    override fun updateIcon(@BiometricState lastState: Int, @BiometricState newState: Int) {
        val icon = getAnimationForTransition(lastState, newState) ?: return

        iconView.setImageDrawable(icon)

        val iconContentDescription = getIconContentDescription(newState)
        if (iconContentDescription != null) {
            iconView.contentDescription = iconContentDescription
        }

        (icon as? AnimatedVectorDrawable)?.apply {
            reset()
            if (shouldAnimateForTransition(lastState, newState)) {
                forceAnimationOnUI()
                start()
            }
        }
    }

    private fun getIconContentDescription(@BiometricState newState: Int): CharSequence? {
        val id = when (newState) {
            STATE_IDLE,
            STATE_AUTHENTICATING_ANIMATING_IN,
            STATE_AUTHENTICATING,
            STATE_PENDING_CONFIRMATION,
            STATE_AUTHENTICATED -> R.string.accessibility_fingerprint_dialog_fingerprint_icon
            STATE_ERROR,
            STATE_HELP -> R.string.biometric_dialog_try_again
            else -> null
        }
        return if (id != null) context.getString(id) else null
    }

    protected open fun shouldAnimateForTransition(
        @BiometricState oldState: Int,
        @BiometricState newState: Int
    ) = when (newState) {
        STATE_HELP,
        STATE_ERROR -> true
        STATE_AUTHENTICATING_ANIMATING_IN,
        STATE_AUTHENTICATING -> oldState == STATE_ERROR || oldState == STATE_HELP
        STATE_AUTHENTICATED -> false
        else -> false
    }

    protected open fun getAnimationForTransition(
        @BiometricState oldState: Int,
        @BiometricState newState: Int
    ): Drawable? {
        val id = when (newState) {
            STATE_HELP,
            STATE_ERROR -> R.drawable.fingerprint_dialog_fp_to_error
            STATE_AUTHENTICATING_ANIMATING_IN,
            STATE_AUTHENTICATING -> {
                if (oldState == STATE_ERROR || oldState == STATE_HELP) {
                    R.drawable.fingerprint_dialog_error_to_fp
                } else {
                    R.drawable.fingerprint_dialog_fp_to_error
                }
            }
            STATE_AUTHENTICATED -> R.drawable.fingerprint_dialog_fp_to_error
            else -> return null
        }
        return if (id != null) context.getDrawable(id) else null
    }
}
