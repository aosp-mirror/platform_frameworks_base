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
import android.os.UserHandle;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImeAwareEditText;
import android.widget.TextView;

import com.android.internal.widget.LockPatternChecker;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.systemui.R;

/**
 * Pin and Password UI
 */
public class AuthCredentialPasswordView extends AuthCredentialView
        implements TextView.OnEditorActionListener {

    private static final String TAG = "BiometricPrompt/AuthCredentialPasswordView";

    private final InputMethodManager mImm;
    private ImeAwareEditText mPasswordField;

    public AuthCredentialPasswordView(Context context,
            AttributeSet attrs) {
        super(context, attrs);
        mImm = mContext.getSystemService(InputMethodManager.class);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mPasswordField = findViewById(R.id.lockPassword);
        mPasswordField.setOnEditorActionListener(this);
        // TODO: De-dupe the logic with AuthContainerView
        mPasswordField.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mContainerView.sendEarlyUserCanceled();
                mContainerView.animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
            }
            return true;
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mPasswordField.setTextOperationUser(UserHandle.of(mUserId));
        if (mCredentialType == Utils.CREDENTIAL_PIN) {
            mPasswordField.setInputType(
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        }

        mPasswordField.requestFocus();
        mPasswordField.scheduleShowSoftInput();
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        // Check if this was the result of hitting the enter key
        final boolean isSoftImeEvent = event == null
                && (actionId == EditorInfo.IME_NULL
                || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_NEXT);
        final boolean isKeyboardEnterKey = event != null
                && KeyEvent.isConfirmKey(event.getKeyCode())
                && event.getAction() == KeyEvent.ACTION_DOWN;
        if (isSoftImeEvent || isKeyboardEnterKey) {
            checkPasswordAndUnlock();
            return true;
        }
        return false;
    }

    private void checkPasswordAndUnlock() {
        try (LockscreenCredential password = mCredentialType == Utils.CREDENTIAL_PIN
                ? LockscreenCredential.createPinOrNone(mPasswordField.getText())
                : LockscreenCredential.createPasswordOrNone(mPasswordField.getText())) {
            if (password.isNone()) {
                return;
            }

            // Request LockSettingsService to return the Gatekeeper Password in the
            // VerifyCredentialResponse so that we can request a Gatekeeper HAT with the
            // Gatekeeper Password and operationId.
            mPendingLockCheck = LockPatternChecker.verifyCredential(mLockPatternUtils,
                    password, mEffectiveUserId, LockPatternUtils.VERIFY_FLAG_REQUEST_GK_PW_HANDLE,
                    this::onCredentialVerified);
        }
    }

    @Override
    protected void onCredentialVerified(@NonNull VerifyCredentialResponse response,
            int timeoutMs) {
        super.onCredentialVerified(response, timeoutMs);

        if (response.isMatched()) {
            mImm.hideSoftInputFromWindow(getWindowToken(), 0 /* flags */);
        } else {
            mPasswordField.setText("");
        }
    }
}
