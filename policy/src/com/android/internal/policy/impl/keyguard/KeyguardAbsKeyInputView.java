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

package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.graphics.Rect;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import com.android.internal.R;
import com.android.internal.widget.LockPatternUtils;

/**
 * Base class for PIN and password unlock screens.
 */
public abstract class KeyguardAbsKeyInputView extends LinearLayout
        implements KeyguardSecurityView, OnEditorActionListener, TextWatcher {
    protected KeyguardSecurityCallback mCallback;
    protected TextView mPasswordEntry;
    protected LockPatternUtils mLockPatternUtils;
    protected SecurityMessageDisplay mSecurityMessageDisplay;
    protected boolean mEnableHaptics;

    // To avoid accidental lockout due to events while the device in in the pocket, ignore
    // any passwords with length less than or equal to this length.
    protected static final int MINIMUM_PASSWORD_LENGTH_BEFORE_REPORT = 3;

    public KeyguardAbsKeyInputView(Context context) {
        this(context, null);
    }

    public KeyguardAbsKeyInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
        mEnableHaptics = mLockPatternUtils.isTactileFeedbackEnabled();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        if (hasWindowFocus) {
            reset();
        }
    }

    public void reset() {
        // start fresh
        mPasswordEntry.setText("");
        mPasswordEntry.requestFocus();

        // if the user is currently locked out, enforce it.
        long deadline = mLockPatternUtils.getLockoutAttemptDeadline();
        if (deadline != 0) {
            handleAttemptLockout(deadline);
        } else {
            resetState();
        }
    }

    protected abstract void resetState();

    @Override
    protected void onFinishInflate() {
        mLockPatternUtils = new LockPatternUtils(mContext);

        mPasswordEntry = (TextView) findViewById(R.id.passwordEntry);
        mPasswordEntry.setOnEditorActionListener(this);
        mPasswordEntry.addTextChangedListener(this);

        // Poke the wakelock any time the text is selected or modified
        mPasswordEntry.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                mCallback.userActivity(0); // TODO: customize timeout for text?
            }
        });

        mPasswordEntry.addTextChangedListener(new TextWatcher() {
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void afterTextChanged(Editable s) {
                if (mCallback != null) {
                    mCallback.userActivity(0);
                }
            }
        });
        mSecurityMessageDisplay = new KeyguardMessageArea.Helper(this);
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // send focus to the password field
        return mPasswordEntry.requestFocus(direction, previouslyFocusedRect);
    }

    protected void verifyPasswordAndUnlock() {
        String entry = mPasswordEntry.getText().toString();
        if (mLockPatternUtils.checkPassword(entry)) {
            mCallback.reportSuccessfulUnlockAttempt();
            mCallback.dismiss(true);
        } else if (entry.length() > MINIMUM_PASSWORD_LENGTH_BEFORE_REPORT ) {
            // to avoid accidental lockout, only count attempts that are long enough to be a
            // real password. This may require some tweaking.
            mCallback.reportFailedUnlockAttempt();
            if (0 == (mCallback.getFailedAttempts()
                    % LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT)) {
                long deadline = mLockPatternUtils.setLockoutAttemptDeadline();
                handleAttemptLockout(deadline);
            }
            mSecurityMessageDisplay.setMessage(R.string.kg_wrong_pin, true);
        }
        mPasswordEntry.setText("");
    }

    // Prevent user from using the PIN/Password entry until scheduled deadline.
    protected void handleAttemptLockout(long elapsedRealtimeDeadline) {
        mPasswordEntry.setEnabled(false);
        long elapsedRealtime = SystemClock.elapsedRealtime();
        new CountDownTimer(elapsedRealtimeDeadline - elapsedRealtime, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                int secondsRemaining = (int) (millisUntilFinished / 1000);
                mSecurityMessageDisplay.setMessage(
                        R.string.kg_too_many_failed_attempts_countdown, true, secondsRemaining);
            }

            @Override
            public void onFinish() {
                resetState();
            }
        }.start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        mCallback.userActivity(0);
        return false;
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        // Check if this was the result of hitting the enter key
        if (actionId == EditorInfo.IME_NULL || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_NEXT) {
            verifyPasswordAndUnlock();
            return true;
        }
        return false;
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {

    }

    @Override
    public void onResume() {
        reset();
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        if (mCallback != null) {
            mCallback.userActivity(KeyguardViewManager.DIGIT_PRESS_WAKE_MILLIS);
        }
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
    }

    // Cause a VIRTUAL_KEY vibration
    public void doHapticKeyClick() {
        if (mEnableHaptics) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                    | HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        }
    }
}

