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

import static com.android.internal.util.LatencyTracker.ACTION_CHECK_CREDENTIAL;
import static com.android.internal.util.LatencyTracker.ACTION_CHECK_CREDENTIAL_UNLOCKED;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternChecker;
import com.android.internal.widget.LockPatternUtils;

import java.util.Arrays;

/**
 * Base class for PIN and password unlock screens.
 */
public abstract class KeyguardAbsKeyInputView extends LinearLayout
        implements KeyguardSecurityView, EmergencyButton.EmergencyButtonCallback {
    protected KeyguardSecurityCallback mCallback;
    protected LockPatternUtils mLockPatternUtils;
    protected AsyncTask<?, ?, ?> mPendingLockCheck;
    protected SecurityMessageDisplay mSecurityMessageDisplay;
    protected View mEcaView;
    protected boolean mEnableHaptics;
    private boolean mDismissing;
    protected boolean mResumed;
    private CountDownTimer mCountdownTimer = null;

    // To avoid accidental lockout due to events while the device in in the pocket, ignore
    // any passwords with length less than or equal to this length.
    protected static final int MINIMUM_PASSWORD_LENGTH_BEFORE_REPORT = 3;

    public KeyguardAbsKeyInputView(Context context) {
        this(context, null);
    }

    public KeyguardAbsKeyInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    @Override
    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
        mEnableHaptics = mLockPatternUtils.isTactileFeedbackEnabled();
    }

    @Override
    public void reset() {
        // start fresh
        mDismissing = false;
        resetPasswordText(false /* animate */, false /* announce */);
        // if the user is currently locked out, enforce it.
        long deadline = mLockPatternUtils.getLockoutAttemptDeadline(
                KeyguardUpdateMonitor.getCurrentUser());
        if (shouldLockout(deadline)) {
            handleAttemptLockout(deadline);
        } else {
            resetState();
        }
    }

    // Allow subclasses to override this behavior
    protected boolean shouldLockout(long deadline) {
        return deadline != 0;
    }

    protected abstract int getPasswordTextViewId();
    protected abstract void resetState();

    @Override
    protected void onFinishInflate() {
        mLockPatternUtils = new LockPatternUtils(mContext);
        mEcaView = findViewById(R.id.keyguard_selector_fade_container);

        EmergencyButton button = findViewById(R.id.emergency_call_button);
        if (button != null) {
            button.setCallback(this);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mSecurityMessageDisplay = KeyguardMessageArea.findSecurityMessageDisplay(this);
    }

    @Override
    public void onEmergencyButtonClickedWhenInCall() {
        mCallback.reset();
    }

    /*
     * Override this if you have a different string for "wrong password"
     *
     * Note that PIN/PUK have their own implementation of verifyPasswordAndUnlock and so don't need this
     */
    protected int getWrongPasswordStringId() {
        return R.string.kg_wrong_password;
    }

    protected void verifyPasswordAndUnlock() {
        if (mDismissing) return; // already verified but haven't been dismissed; don't do it again.

        final byte[] entry = getPasswordText();
        setPasswordEntryInputEnabled(false);
        if (mPendingLockCheck != null) {
            mPendingLockCheck.cancel(false);
        }

        final int userId = KeyguardUpdateMonitor.getCurrentUser();
        if (entry.length <= MINIMUM_PASSWORD_LENGTH_BEFORE_REPORT) {
            // to avoid accidental lockout, only count attempts that are long enough to be a
            // real password. This may require some tweaking.
            setPasswordEntryInputEnabled(true);
            onPasswordChecked(userId, false /* matched */, 0, false /* not valid - too short */);
            Arrays.fill(entry, (byte) 0);
            return;
        }

        if (LatencyTracker.isEnabled(mContext)) {
            LatencyTracker.getInstance(mContext).onActionStart(ACTION_CHECK_CREDENTIAL);
            LatencyTracker.getInstance(mContext).onActionStart(ACTION_CHECK_CREDENTIAL_UNLOCKED);
        }
        mPendingLockCheck = LockPatternChecker.checkPassword(
                mLockPatternUtils,
                entry,
                userId,
                new LockPatternChecker.OnCheckCallback() {

                    @Override
                    public void onEarlyMatched() {
                        if (LatencyTracker.isEnabled(mContext)) {
                            LatencyTracker.getInstance(mContext).onActionEnd(
                                    ACTION_CHECK_CREDENTIAL);
                        }
                        onPasswordChecked(userId, true /* matched */, 0 /* timeoutMs */,
                                true /* isValidPassword */);
                        Arrays.fill(entry, (byte) 0);
                    }

                    @Override
                    public void onChecked(boolean matched, int timeoutMs) {
                        if (LatencyTracker.isEnabled(mContext)) {
                            LatencyTracker.getInstance(mContext).onActionEnd(
                                    ACTION_CHECK_CREDENTIAL_UNLOCKED);
                        }
                        setPasswordEntryInputEnabled(true);
                        mPendingLockCheck = null;
                        if (!matched) {
                            onPasswordChecked(userId, false /* matched */, timeoutMs,
                                    true /* isValidPassword */);
                        }
                        Arrays.fill(entry, (byte) 0);
                    }

                    @Override
                    public void onCancelled() {
                        // We already got dismissed with the early matched callback, so we cancelled
                        // the check. However, we still need to note down the latency.
                        if (LatencyTracker.isEnabled(mContext)) {
                            LatencyTracker.getInstance(mContext).onActionEnd(
                                    ACTION_CHECK_CREDENTIAL_UNLOCKED);
                        }
                        Arrays.fill(entry, (byte) 0);
                    }
                });
    }

    private void onPasswordChecked(int userId, boolean matched, int timeoutMs,
            boolean isValidPassword) {
        boolean dismissKeyguard = KeyguardUpdateMonitor.getCurrentUser() == userId;
        if (matched) {
            mLockPatternUtils.sanitizePassword();
            mCallback.reportUnlockAttempt(userId, true, 0);
            if (dismissKeyguard) {
                mDismissing = true;
                mCallback.dismiss(true, userId);
            }
        } else {
            if (isValidPassword) {
                mCallback.reportUnlockAttempt(userId, false, timeoutMs);
                if (timeoutMs > 0) {
                    long deadline = mLockPatternUtils.setLockoutAttemptDeadline(
                            userId, timeoutMs);
                    handleAttemptLockout(deadline);
                }
            }
            if (timeoutMs == 0) {
                mSecurityMessageDisplay.setMessage(getWrongPasswordStringId());
            }
        }
        resetPasswordText(true /* animate */, !matched /* announce deletion if no match */);
    }

    protected abstract void resetPasswordText(boolean animate, boolean announce);
    protected abstract byte[] getPasswordText();
    protected abstract void setPasswordEntryEnabled(boolean enabled);
    protected abstract void setPasswordEntryInputEnabled(boolean enabled);

    // Prevent user from using the PIN/Password entry until scheduled deadline.
    protected void handleAttemptLockout(long elapsedRealtimeDeadline) {
        setPasswordEntryEnabled(false);
        long elapsedRealtime = SystemClock.elapsedRealtime();
        long secondsInFuture = (long) Math.ceil(
                (elapsedRealtimeDeadline - elapsedRealtime) / 1000.0);
        mCountdownTimer = new CountDownTimer(secondsInFuture * 1000, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                int secondsRemaining = (int) Math.round(millisUntilFinished / 1000.0);
                mSecurityMessageDisplay.setMessage(mContext.getResources().getQuantityString(
                        R.plurals.kg_too_many_failed_attempts_countdown,
                        secondsRemaining, secondsRemaining));
            }

            @Override
            public void onFinish() {
                mSecurityMessageDisplay.setMessage("");
                resetState();
            }
        }.start();
    }

    protected void onUserInput() {
        if (mCallback != null) {
            mCallback.userActivity();
        }
        mSecurityMessageDisplay.setMessage("");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Fingerprint sensor sends a KeyEvent.KEYCODE_UNKNOWN.
        // We don't want to consider it valid user input because the UI
        // will already respond to the event.
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            onUserInput();
        }
        return false;
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {
        mResumed = false;

        if (mCountdownTimer != null) {
            mCountdownTimer.cancel();
            mCountdownTimer = null;
        }
        if (mPendingLockCheck != null) {
            mPendingLockCheck.cancel(false);
            mPendingLockCheck = null;
        }
        reset();
    }

    @Override
    public void onResume(int reason) {
        mResumed = true;
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void showPromptReason(int reason) {
        if (reason != PROMPT_REASON_NONE) {
            int promtReasonStringRes = getPromptReasonStringRes(reason);
            if (promtReasonStringRes != 0) {
                mSecurityMessageDisplay.setMessage(promtReasonStringRes);
            }
        }
    }

    @Override
    public void showMessage(CharSequence message, ColorStateList colorState) {
        mSecurityMessageDisplay.setNextMessageColor(colorState);
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
}

