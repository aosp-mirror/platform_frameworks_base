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

package com.android.systemui.biometrics;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator.Modality;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

public class AuthBiometricFaceView extends AuthBiometricView {

    private static final String TAG = "AuthBiometricFaceView";

    // Delay before dismissing after being authenticated/confirmed.
    private static final int HIDE_DELAY_MS = 500;

    @Nullable @VisibleForTesting AuthBiometricFaceIconController mFaceIconController;
    @NonNull private final OnAttachStateChangeListener mOnAttachStateChangeListener =
            new OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {

        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            mFaceIconController.deactivate();
        }
    };

    public AuthBiometricFaceView(Context context) {
        this(context, null);
    }

    public AuthBiometricFaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mFaceIconController = new AuthBiometricFaceIconController(mContext, mIconView, mIndicatorView);

        addOnAttachStateChangeListener(mOnAttachStateChangeListener);
    }

    @Override
    protected int getDelayAfterAuthenticatedDurationMs() {
        return HIDE_DELAY_MS;
    }

    @Override
    protected int getStateForAfterError() {
        return STATE_IDLE;
    }

    @Override
    protected void handleResetAfterError() {
        resetErrorView();
    }

    @Override
    protected void handleResetAfterHelp() {
        resetErrorView();
    }

    @Override
    protected boolean supportsSmallDialog() {
        return true;
    }

    @Override
    protected boolean supportsManualRetry() {
        return true;
    }

    @Override
    public void updateState(@BiometricState int newState) {
        mFaceIconController.updateState(mState, newState);

        if (newState == STATE_AUTHENTICATING_ANIMATING_IN ||
                (newState == STATE_AUTHENTICATING && getSize() == AuthDialog.SIZE_MEDIUM)) {
            resetErrorView();
        }

        // Do this last since the state variable gets updated.
        super.updateState(newState);
    }

    @Override
    public void onAuthenticationFailed(@Modality int modality, @Nullable String failureReason) {
        if (getSize() == AuthDialog.SIZE_MEDIUM) {
            if (supportsManualRetry()) {
                mTryAgainButton.setVisibility(View.VISIBLE);
                mConfirmButton.setVisibility(View.GONE);
            }
        }

        // Do this last since we want to know if the button is being animated (in the case of
        // small -> medium dialog)
        super.onAuthenticationFailed(modality, failureReason);
    }

    private void resetErrorView() {
        mIndicatorView.setTextColor(mTextColorHint);
        mIndicatorView.setVisibility(View.INVISIBLE);
    }
}
