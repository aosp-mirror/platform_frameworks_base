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

import android.annotation.NonNull;
import android.content.Context;
import android.util.AttributeSet;

import com.android.internal.widget.LockPatternChecker;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.systemui.R;

import java.util.List;

/**
 * Pattern UI
 */
public class AuthCredentialPatternView extends AuthCredentialView {

    private LockPatternView mLockPatternView;

    private class UnlockPatternListener implements LockPatternView.OnPatternListener {

        @Override
        public void onPatternStart() {

        }

        @Override
        public void onPatternCleared() {

        }

        @Override
        public void onPatternCellAdded(List<LockPatternView.Cell> pattern) {

        }

        @Override
        public void onPatternDetected(List<LockPatternView.Cell> pattern) {
            if (mPendingLockCheck != null) {
                mPendingLockCheck.cancel(false);
            }

            mLockPatternView.setEnabled(false);

            if (pattern.size() < LockPatternUtils.MIN_PATTERN_REGISTER_FAIL) {
                // Pattern size is less than the minimum, do not count it as a failed attempt.
                onPatternVerified(VerifyCredentialResponse.ERROR, 0 /* timeoutMs */);
                return;
            }

            try (LockscreenCredential credential = LockscreenCredential.createPattern(pattern)) {
                // Request LockSettingsService to return the Gatekeeper Password in the
                // VerifyCredentialResponse so that we can request a Gatekeeper HAT with the
                // Gatekeeper Password and operationId.
                mPendingLockCheck = LockPatternChecker.verifyCredential(
                        mLockPatternUtils,
                        credential,
                        mEffectiveUserId,
                        LockPatternUtils.VERIFY_FLAG_REQUEST_GK_PW_HANDLE,
                        this::onPatternVerified);
            }
        }

        private void onPatternVerified(@NonNull VerifyCredentialResponse response, int timeoutMs) {
            AuthCredentialPatternView.this.onCredentialVerified(response, timeoutMs);
            if (timeoutMs > 0) {
                mLockPatternView.setEnabled(false);
            } else {
                mLockPatternView.setEnabled(true);
            }
        }
    }

    @Override
    protected void onErrorTimeoutFinish() {
        super.onErrorTimeoutFinish();
        mLockPatternView.setEnabled(true);
    }

    public AuthCredentialPatternView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mLockPatternView = findViewById(R.id.lockPattern);
        mLockPatternView.setOnPatternListener(new UnlockPatternListener());
        mLockPatternView.setInStealthMode(
                !mLockPatternUtils.isVisiblePatternEnabled(mUserId));
        mLockPatternView.setTactileFeedbackEnabled(mLockPatternUtils.isTactileFeedbackEnabled());
    }
}
