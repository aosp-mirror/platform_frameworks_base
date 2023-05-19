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
import android.hardware.biometrics.BiometricAuthenticator.Modality
import android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE
import android.hardware.biometrics.BiometricConstants
import android.hardware.face.FaceManager
import android.util.AttributeSet
import com.android.systemui.R

/** Face/Fingerprint combined view for BiometricPrompt. */
class AuthBiometricFingerprintAndFaceView(
    context: Context,
    attrs: AttributeSet?
) : AuthBiometricFingerprintView(context, attrs) {
    var isFaceClass3 = false

    constructor (context: Context) : this(context, null)

    override fun getConfirmationPrompt() = R.string.biometric_dialog_tap_confirm_with_face

    override fun forceRequireConfirmation(@Modality modality: Int) = modality == TYPE_FACE

    override fun ignoreUnsuccessfulEventsFrom(@Modality modality: Int, unsuccessfulReason: String) =
        modality == TYPE_FACE && !(isFaceClass3 && isLockoutErrorString(unsuccessfulReason))

    override fun createIconController(): AuthIconController =
        AuthBiometricFingerprintAndFaceIconController(mContext, mIconView, mIconViewOverlay)

    override fun isCoex() = true

    private fun isLockoutErrorString(unsuccessfulReason: String) =
        unsuccessfulReason == FaceManager.getErrorString(
            mContext,
            BiometricConstants.BIOMETRIC_ERROR_LOCKOUT,
            0 /*vendorCode */
        ) || unsuccessfulReason == FaceManager.getErrorString(
            mContext,
            BiometricConstants.BIOMETRIC_ERROR_LOCKOUT_PERMANENT,
            0 /*vendorCode */
        )
}
