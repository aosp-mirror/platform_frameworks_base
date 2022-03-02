/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.util.AttributeSet

/** Face only view for BiometricPrompt. */
class AuthBiometricFaceView(
    context: Context,
    attrs: AttributeSet? = null
) : AuthBiometricView(context, attrs) {

    override fun getDelayAfterAuthenticatedDurationMs() = HIDE_DELAY_MS

    override fun getStateForAfterError() = STATE_IDLE

    override fun handleResetAfterError() = resetErrorView()

    override fun handleResetAfterHelp() = resetErrorView()

    override fun supportsSmallDialog() = true

    override fun supportsManualRetry() = true

    override fun supportsRequireConfirmation() = true

    override fun createIconController(): AuthIconController =
        AuthBiometricFaceIconController(mContext, mIconView)

    override fun updateState(@BiometricState newState: Int) {
        if (newState == STATE_AUTHENTICATING_ANIMATING_IN ||
            newState == STATE_AUTHENTICATING && size == AuthDialog.SIZE_MEDIUM) {
            resetErrorView()
        }

        // Do this last since the state variable gets updated.
        super.updateState(newState)
    }

    override fun onAuthenticationFailed(
        @Modality modality: Int,
        failureReason: String?
    ) {
        if (size == AuthDialog.SIZE_MEDIUM) {
            if (supportsManualRetry()) {
                mTryAgainButton.visibility = VISIBLE
                mConfirmButton.visibility = GONE
            }
        }

        // Do this last since we want to know if the button is being animated (in the case of
        // small -> medium dialog)
        super.onAuthenticationFailed(modality, failureReason)
    }

    private fun resetErrorView() {
        mIndicatorView.setTextColor(mTextColorHint)
        mIndicatorView.visibility = INVISIBLE
    }

    companion object {
        /** Delay before dismissing after being authenticated/confirmed. */
        const val HIDE_DELAY_MS = 500
    }
}
