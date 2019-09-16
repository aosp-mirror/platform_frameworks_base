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
import android.hardware.biometrics.BiometricPrompt;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.widget.LockPatternChecker;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;
import com.android.systemui.Interpolators;
import com.android.systemui.R;

import java.util.List;

/**
 * Shows Pin, Pattern, or Password for
 * {@link BiometricPrompt.Builder#setDeviceCredentialAllowed(boolean)}
 */
public class AuthCredentialView extends LinearLayout {

    private static final int ERROR_DURATION_MS = 3000;

    private final AccessibilityManager mAccessibilityManager;
    private final LockPatternUtils mLockPatternUtils;
    private final Handler mHandler;

    private LockPatternView mLockPatternView;
    private int mUserId;
    private AsyncTask<?, ?, ?> mPendingLockCheck;
    private Callback mCallback;
    private ErrorTimer mErrorTimer;
    private Bundle mBiometricPromptBundle;

    private TextView mTitleView;
    private TextView mSubtitleView;
    private TextView mDescriptionView;
    private TextView mErrorView;

    interface Callback {
        void onCredentialMatched();
    }

    private static class ErrorTimer extends CountDownTimer {
        private final TextView mErrorView;
        private final Context mContext;

        /**
         * @param millisInFuture    The number of millis in the future from the call
         *                          to {@link #start()} until the countdown is done and {@link
         *                          #onFinish()}
         *                          is called.
         * @param countDownInterval The interval along the way to receive
         *                          {@link #onTick(long)} callbacks.
         */
        public ErrorTimer(Context context, long millisInFuture, long countDownInterval,
                TextView errorView) {
            super(millisInFuture, countDownInterval);
            mErrorView = errorView;
            mContext = context;
        }

        @Override
        public void onTick(long millisUntilFinished) {
            final int secondsCountdown = (int) (millisUntilFinished / 1000);
            mErrorView.setText(mContext.getString(
                    R.string.biometric_dialog_credential_too_many_attempts, secondsCountdown));
        }

        @Override
        public void onFinish() {
            mErrorView.setText("");
        }
    }

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
                onPatternChecked(false /* matched */, 0 /* timeoutMs */);
                return;
            }

            mPendingLockCheck = LockPatternChecker.checkPattern(
                    mLockPatternUtils,
                    pattern,
                    mUserId,
                    this::onPatternChecked);
        }

        private void onPatternChecked(boolean matched, int timeoutMs) {
            mLockPatternView.setEnabled(true);

            if (matched) {
                mClearErrorRunnable.run();
                mCallback.onCredentialMatched();
            } else {
                if (timeoutMs > 0) {
                    mHandler.removeCallbacks(mClearErrorRunnable);
                    mLockPatternView.setEnabled(false);
                    long deadline = mLockPatternUtils.setLockoutAttemptDeadline(mUserId, timeoutMs);
                    mErrorTimer = new ErrorTimer(mContext,
                            deadline - SystemClock.elapsedRealtime(),
                            LockPatternUtils.FAILED_ATTEMPT_COUNTDOWN_INTERVAL_MS,
                            mErrorView) {
                        @Override
                        public void onFinish() {
                            mClearErrorRunnable.run();
                            mLockPatternView.setEnabled(true);
                        }
                    };
                    mErrorTimer.start();
                } else {
                    showError(getResources().getString(R.string.biometric_dialog_wrong_pattern));
                }
            }
        }
    }

    private final Runnable mClearErrorRunnable = new Runnable() {
        @Override
        public void run() {
            mErrorView.setText("");
        }
    };

    public AuthCredentialView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mHandler = new Handler(Looper.getMainLooper());
        mLockPatternUtils = new LockPatternUtils(mContext);
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
    }

    private void showError(String error) {
        mHandler.removeCallbacks(mClearErrorRunnable);
        mErrorView.setText(error);
        mHandler.postDelayed(mClearErrorRunnable, ERROR_DURATION_MS);
    }

    private void setTextOrHide(TextView view, String string) {
        if (TextUtils.isEmpty(string)) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(string);
        }

        Utils.notifyAccessibilityContentChanged(mAccessibilityManager, this);
    }

    private void setText(TextView view, String string) {
        view.setText(string);
    }

    void setUser(int user) {
        mUserId = user;
    }

    void setCallback(Callback callback) {
        mCallback = callback;
    }

    void setBiometricPromptBundle(Bundle bundle) {
        mBiometricPromptBundle = bundle;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        setText(mTitleView, mBiometricPromptBundle.getString(BiometricPrompt.KEY_TITLE));
        setTextOrHide(mSubtitleView,
                mBiometricPromptBundle.getString(BiometricPrompt.KEY_SUBTITLE));
        setTextOrHide(mDescriptionView,
                mBiometricPromptBundle.getString(BiometricPrompt.KEY_DESCRIPTION));

        setTranslationY(getResources()
                .getDimension(R.dimen.biometric_dialog_credential_translation_offset));
        setAlpha(0);

        postOnAnimation(() -> {
           animate().translationY(0)
                   .setDuration(AuthDialog.ANIMATE_CREDENTIAL_INITIAL_DURATION_MS)
                   .alpha(1.f)
                   .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                   .withLayer()
                   .start();
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mErrorTimer != null) {
            mErrorTimer.cancel();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTitleView = findViewById(R.id.title);
        mSubtitleView = findViewById(R.id.subtitle);
        mDescriptionView = findViewById(R.id.description);
        mErrorView = findViewById(R.id.error);
        mLockPatternView = findViewById(R.id.lockPattern);
        mLockPatternView.setOnPatternListener(new UnlockPatternListener());
        mLockPatternView.setInStealthMode(!mLockPatternUtils.isVisiblePatternEnabled(mUserId));
        mLockPatternView.setTactileFeedbackEnabled(mLockPatternUtils.isTactileFeedbackEnabled());
    }

}
