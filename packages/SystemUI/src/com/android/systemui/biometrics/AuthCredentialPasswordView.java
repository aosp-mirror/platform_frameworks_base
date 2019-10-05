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
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import com.android.internal.widget.LockPatternChecker;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.R;

/**
 * Pin and Password UI
 */
public class AuthCredentialPasswordView extends AuthCredentialView
        implements TextView.OnEditorActionListener {

    private static final String TAG = "BiometricPrompt/AuthCredentialPasswordView";

    private final InputMethodManager mImm;
    private EditText mPasswordField;

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
        mPasswordField.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mContainerView.animateAway(AuthDialogCallback.DISMISSED_USER_CANCELED);
            }
            return true;
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mCredentialType == Utils.CREDENTIAL_PIN) {
            mPasswordField.setInputType(
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        }

        // Wait a bit to focus the field so the focusable flag on the window is already set then.
        post(() -> {
            mPasswordField.requestFocus();
            mImm.showSoftInput(mPasswordField, InputMethodManager.SHOW_IMPLICIT);
        });
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
        final byte[] password = LockPatternUtils.charSequenceToByteArray(mPasswordField.getText());
        if (password == null || password.length == 0) {
            return;
        }

        mPendingLockCheck = LockPatternChecker.checkPassword(mLockPatternUtils,
                password, mUserId, this::onCredentialChecked);
    }

    @Override
    protected void onCredentialChecked(boolean matched, int timeoutMs) {
        super.onCredentialChecked(matched, timeoutMs);

        if (matched) {
            mImm.hideSoftInputFromWindow(getWindowToken(), 0 /* flags */);
        } else {
            mPasswordField.setText("");
        }
    }
}
