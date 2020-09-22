/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.systemui.R;

/**
 * Base class for PIN and password unlock screens.
 */
public abstract class KeyguardAbsKeyInputView extends KeyguardInputView {
    protected KeyguardSecurityCallback mCallback;
    protected SecurityMessageDisplay mSecurityMessageDisplay;
    protected View mEcaView;
    protected boolean mEnableHaptics;

    // To avoid accidental lockout due to events while the device in in the pocket, ignore
    // any passwords with length less than or equal to this length.
    protected static final int MINIMUM_PASSWORD_LENGTH_BEFORE_REPORT = 3;
    private KeyDownListener mKeyDownListener;

    public KeyguardAbsKeyInputView(Context context) {
        this(context, null);
    }

    public KeyguardAbsKeyInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setEnableHaptics(boolean enableHaptics) {
        mEnableHaptics = enableHaptics;
    }

    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    @Override
    public void setLockPatternUtils(LockPatternUtils utils) {
    }

    @Override
    public void reset() {
    }

    protected abstract int getPasswordTextViewId();
    protected abstract void resetState();

    @Override
    protected void onFinishInflate() {
        mEcaView = findViewById(R.id.keyguard_selector_fade_container);
    }

    /*
     * Override this if you have a different string for "wrong password"
     *
     * Note that PIN/PUK have their own implementation of verifyPasswordAndUnlock and so don't need this
     */
    protected int getWrongPasswordStringId() {
        return R.string.kg_wrong_password;
    }

    protected abstract void resetPasswordText(boolean animate, boolean announce);
    protected abstract LockscreenCredential getEnteredCredential();
    protected abstract void setPasswordEntryEnabled(boolean enabled);
    protected abstract void setPasswordEntryInputEnabled(boolean enabled);

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mKeyDownListener != null && mKeyDownListener.onKeyDown(keyCode, event);
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void showPromptReason(int reason) {

    }

    @Override
    public void showMessage(CharSequence message, ColorStateList colorState) {
        if (colorState != null) {
            mSecurityMessageDisplay.setNextMessageColor(colorState);
        }
        mSecurityMessageDisplay.setMessage(message);
    }

    protected abstract int getPromptReasonStringRes(int reason);

    // Cause a VIRTUAL_KEY vibration
    public void doHapticKeyClick() {
        if (mEnableHaptics) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                    | HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }

    @Override
    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return false;
    }

    public void setKeyDownListener(KeyDownListener keyDownListener) {
        mKeyDownListener = keyDownListener;
    }

    public interface KeyDownListener {
        boolean onKeyDown(int keyCode, KeyEvent keyEvent);
    }
}

