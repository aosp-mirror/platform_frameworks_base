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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.Button;
import android.widget.LinearLayout;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;

import java.io.IOException;
import java.util.List;

public class KeyguardPatternView extends LinearLayout implements KeyguardSecurityView,
        AppearAnimationCreator<LockPatternView.CellState> {

    private static final String TAG = "SecurityPatternView";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

    // how long before we clear the wrong pattern
    private static final int PATTERN_CLEAR_TIMEOUT_MS = 2000;

    // how long we stay awake after each key beyond MIN_PATTERN_BEFORE_POKE_WAKELOCK
    private static final int UNLOCK_PATTERN_WAKE_INTERVAL_MS = 7000;

    // how many cells the user has to cross before we poke the wakelock
    private static final int MIN_PATTERN_BEFORE_POKE_WAKELOCK = 2;

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final AppearAnimationUtils mAppearAnimationUtils;

    private CountDownTimer mCountdownTimer = null;
    private LockPatternUtils mLockPatternUtils;
    private LockPatternView mLockPatternView;
    private Button mForgotPatternButton;
    private KeyguardSecurityCallback mCallback;
    private boolean mEnableFallback;

    /**
     * Keeps track of the last time we poked the wake lock during dispatching of the touch event.
     * Initialized to something guaranteed to make us poke the wakelock when the user starts
     * drawing the pattern.
     * @see #dispatchTouchEvent(android.view.MotionEvent)
     */
    private long mLastPokeTime = -UNLOCK_PATTERN_WAKE_INTERVAL_MS;

    /**
     * Useful for clearing out the wrong pattern after a delay
     */
    private Runnable mCancelPatternRunnable = new Runnable() {
        public void run() {
            mLockPatternView.clearPattern();
        }
    };
    private Rect mTempRect = new Rect();
    private SecurityMessageDisplay mSecurityMessageDisplay;
    private View mEcaView;
    private Drawable mBouncerFrame;
    private ViewGroup mKeyguardBouncerFrame;
    private KeyguardMessageArea mHelpMessage;

    enum FooterMode {
        Normal,
        ForgotLockPattern,
        VerifyUnlocked
    }

    public KeyguardPatternView(Context context) {
        this(context, null);
    }

    public KeyguardPatternView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
        mAppearAnimationUtils = new AppearAnimationUtils(context,
                AppearAnimationUtils.DEFAULT_APPEAR_DURATION, 1.5f /* delayScale */,
                2.0f /* transitionScale */, AnimationUtils.loadInterpolator(
                        mContext, android.R.interpolator.linear_out_slow_in));
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLockPatternUtils = mLockPatternUtils == null
                ? new LockPatternUtils(mContext) : mLockPatternUtils;

        mLockPatternView = (LockPatternView) findViewById(R.id.lockPatternView);
        mLockPatternView.setSaveEnabled(false);
        mLockPatternView.setFocusable(false);
        mLockPatternView.setOnPatternListener(new UnlockPatternListener());

        // stealth mode will be the same for the life of this screen
        mLockPatternView.setInStealthMode(!mLockPatternUtils.isVisiblePatternEnabled());

        // vibrate mode will be the same for the life of this screen
        mLockPatternView.setTactileFeedbackEnabled(mLockPatternUtils.isTactileFeedbackEnabled());

        mForgotPatternButton = (Button) findViewById(R.id.forgot_password_button);
        // note: some configurations don't have an emergency call area
        if (mForgotPatternButton != null) {
            mForgotPatternButton.setText(R.string.kg_forgot_pattern_button_text);
            mForgotPatternButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    mCallback.showBackupSecurity();
                }
            });
        }

        setFocusableInTouchMode(true);

        maybeEnableFallback(mContext);
        mSecurityMessageDisplay = new KeyguardMessageArea.Helper(this);
        mEcaView = findViewById(R.id.keyguard_selector_fade_container);
        View bouncerFrameView = findViewById(R.id.keyguard_bouncer_frame);
        if (bouncerFrameView != null) {
            mBouncerFrame = bouncerFrameView.getBackground();
        }

        mKeyguardBouncerFrame = (ViewGroup) findViewById(R.id.keyguard_bouncer_frame);
        mHelpMessage = (KeyguardMessageArea) findViewById(R.id.keyguard_message_area);
    }

    private void updateFooter(FooterMode mode) {
        if (mForgotPatternButton == null) return; // no ECA? no footer

        switch (mode) {
            case Normal:
                if (DEBUG) Log.d(TAG, "mode normal");
                mForgotPatternButton.setVisibility(View.GONE);
                break;
            case ForgotLockPattern:
                if (DEBUG) Log.d(TAG, "mode ForgotLockPattern");
                mForgotPatternButton.setVisibility(View.VISIBLE);
                break;
            case VerifyUnlocked:
                if (DEBUG) Log.d(TAG, "mode VerifyUnlocked");
                mForgotPatternButton.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean result = super.onTouchEvent(ev);
        // as long as the user is entering a pattern (i.e sending a touch event that was handled
        // by this screen), keep poking the wake lock so that the screen will stay on.
        final long elapsed = SystemClock.elapsedRealtime() - mLastPokeTime;
        if (result && (elapsed > (UNLOCK_PATTERN_WAKE_INTERVAL_MS - 100))) {
            mLastPokeTime = SystemClock.elapsedRealtime();
        }
        mTempRect.set(0, 0, 0, 0);
        offsetRectIntoDescendantCoords(mLockPatternView, mTempRect);
        ev.offsetLocation(mTempRect.left, mTempRect.top);
        result = mLockPatternView.dispatchTouchEvent(ev) || result;
        ev.offsetLocation(-mTempRect.left, -mTempRect.top);
        return result;
    }

    public void reset() {
        // reset lock pattern
        mLockPatternView.enableInput();
        mLockPatternView.setEnabled(true);
        mLockPatternView.clearPattern();

        // if the user is currently locked out, enforce it.
        long deadline = mLockPatternUtils.getLockoutAttemptDeadline();
        if (deadline != 0) {
            handleAttemptLockout(deadline);
        } else {
            displayDefaultSecurityMessage();
        }

        // the footer depends on how many total attempts the user has failed
        if (mCallback.isVerifyUnlockOnly()) {
            updateFooter(FooterMode.VerifyUnlocked);
        } else if (mEnableFallback &&
                (mKeyguardUpdateMonitor.getFailedUnlockAttempts()
                        >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT)) {
            updateFooter(FooterMode.ForgotLockPattern);
        } else {
            updateFooter(FooterMode.Normal);
        }

    }

    private void displayDefaultSecurityMessage() {
        if (mKeyguardUpdateMonitor.getMaxBiometricUnlockAttemptsReached()) {
            mSecurityMessageDisplay.setMessage(R.string.faceunlock_multiple_failures, true);
        } else {
            mSecurityMessageDisplay.setMessage(R.string.kg_pattern_instructions, false);
        }
    }

    @Override
    public void showUsabilityHint() {
    }

    /** TODO: hook this up */
    public void cleanUp() {
        if (DEBUG) Log.v(TAG, "Cleanup() called on " + this);
        mLockPatternUtils = null;
        mLockPatternView.setOnPatternListener(null);
    }

    private class UnlockPatternListener implements LockPatternView.OnPatternListener {

        public void onPatternStart() {
            mLockPatternView.removeCallbacks(mCancelPatternRunnable);
        }

        public void onPatternCleared() {
        }

        public void onPatternCellAdded(List<LockPatternView.Cell> pattern) {
            mCallback.userActivity();
        }

        public void onPatternDetected(List<LockPatternView.Cell> pattern) {
            if (mLockPatternUtils.checkPattern(pattern)) {
                mCallback.reportUnlockAttempt(true);
                mLockPatternView.setDisplayMode(LockPatternView.DisplayMode.Correct);
                mCallback.dismiss(true);
            } else {
                if (pattern.size() > MIN_PATTERN_BEFORE_POKE_WAKELOCK) {
                    mCallback.userActivity();
                }
                mLockPatternView.setDisplayMode(LockPatternView.DisplayMode.Wrong);
                boolean registeredAttempt =
                        pattern.size() >= LockPatternUtils.MIN_PATTERN_REGISTER_FAIL;
                if (registeredAttempt) {
                    mCallback.reportUnlockAttempt(false);
                }
                int attempts = mKeyguardUpdateMonitor.getFailedUnlockAttempts();
                if (registeredAttempt &&
                        0 == (attempts % LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT)) {
                    long deadline = mLockPatternUtils.setLockoutAttemptDeadline();
                    handleAttemptLockout(deadline);
                } else {
                    mSecurityMessageDisplay.setMessage(R.string.kg_wrong_pattern, true);
                    mLockPatternView.postDelayed(mCancelPatternRunnable, PATTERN_CLEAR_TIMEOUT_MS);
                }
            }
        }
    }

    private void maybeEnableFallback(Context context) {
        // Ask the account manager if we have an account that can be used as a
        // fallback in case the user forgets his pattern.
        AccountAnalyzer accountAnalyzer = new AccountAnalyzer(AccountManager.get(context));
        accountAnalyzer.start();
    }

    private class AccountAnalyzer implements AccountManagerCallback<Bundle> {
        private final AccountManager mAccountManager;
        private final Account[] mAccounts;
        private int mAccountIndex;

        private AccountAnalyzer(AccountManager accountManager) {
            mAccountManager = accountManager;
            mAccounts = accountManager.getAccountsByTypeAsUser("com.google",
                    new UserHandle(mLockPatternUtils.getCurrentUser()));
        }

        private void next() {
            // if we are ready to enable the fallback or if we depleted the list of accounts
            // then finish and get out
            if (mEnableFallback || mAccountIndex >= mAccounts.length) {
                return;
            }

            // lookup the confirmCredentials intent for the current account
            mAccountManager.confirmCredentialsAsUser(mAccounts[mAccountIndex], null, null, this,
                    null, new UserHandle(mLockPatternUtils.getCurrentUser()));
        }

        public void start() {
            mEnableFallback = false;
            mAccountIndex = 0;
            next();
        }

        public void run(AccountManagerFuture<Bundle> future) {
            try {
                Bundle result = future.getResult();
                if (result.getParcelable(AccountManager.KEY_INTENT) != null) {
                    mEnableFallback = true;
                }
            } catch (OperationCanceledException e) {
                // just skip the account if we are unable to query it
            } catch (IOException e) {
                // just skip the account if we are unable to query it
            } catch (AuthenticatorException e) {
                // just skip the account if we are unable to query it
            } finally {
                mAccountIndex++;
                next();
            }
        }
    }

    private void handleAttemptLockout(long elapsedRealtimeDeadline) {
        mLockPatternView.clearPattern();
        mLockPatternView.setEnabled(false);
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        if (mEnableFallback) {
            updateFooter(FooterMode.ForgotLockPattern);
        }

        mCountdownTimer = new CountDownTimer(elapsedRealtimeDeadline - elapsedRealtime, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                final int secondsRemaining = (int) (millisUntilFinished / 1000);
                mSecurityMessageDisplay.setMessage(
                        R.string.kg_too_many_failed_attempts_countdown, true, secondsRemaining);
            }

            @Override
            public void onFinish() {
                mLockPatternView.setEnabled(true);
                displayDefaultSecurityMessage();
                // TODO mUnlockIcon.setVisibility(View.VISIBLE);
                if (mEnableFallback) {
                    updateFooter(FooterMode.ForgotLockPattern);
                } else {
                    updateFooter(FooterMode.Normal);
                }
            }

        }.start();
    }

    @Override
    public boolean needsInput() {
        return false;
    }

    @Override
    public void onPause() {
        if (mCountdownTimer != null) {
            mCountdownTimer.cancel();
            mCountdownTimer = null;
        }
    }

    @Override
    public void onResume(int reason) {
        reset();
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

    @Override
    public void showBouncer(int duration) {
        KeyguardSecurityViewHelper.
                showBouncer(mSecurityMessageDisplay, mEcaView, mBouncerFrame, duration);
    }

    @Override
    public void hideBouncer(int duration) {
        KeyguardSecurityViewHelper.
                hideBouncer(mSecurityMessageDisplay, mEcaView, mBouncerFrame, duration);
    }

    @Override
    public void startAppearAnimation() {
        enableClipping(false);
        mAppearAnimationUtils.startAppearAnimation(
                mLockPatternView.getCellStates(),
                new Runnable() {
                    @Override
                    public void run() {
                        enableClipping(true);
                    }
                },
                this);
        if (!TextUtils.isEmpty(mHelpMessage.getText())) {
            mAppearAnimationUtils.createAnimation(mHelpMessage, 0,
                    AppearAnimationUtils.DEFAULT_APPEAR_DURATION,
                    mAppearAnimationUtils.getStartTranslation(),
                    mAppearAnimationUtils.getInterpolator(),
                    null /* finishRunnable */);
        }
    }

    private void enableClipping(boolean enable) {
        setClipChildren(enable);
        mKeyguardBouncerFrame.setClipToPadding(enable);
        mKeyguardBouncerFrame.setClipChildren(enable);
    }

    @Override
    public void createAnimation(final LockPatternView.CellState animatedCell, long delay,
            long duration, float startTranslationY, Interpolator interpolator,
            final Runnable finishListener) {
        animatedCell.scale = 0.0f;
        animatedCell.translateY = startTranslationY;
        ValueAnimator animator = ValueAnimator.ofFloat(startTranslationY, 0.0f);
        animator.setInterpolator(interpolator);
        animator.setDuration(duration);
        animator.setStartDelay(delay);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float animatedFraction = animation.getAnimatedFraction();
                animatedCell.scale = animatedFraction;
                animatedCell.translateY = (float) animation.getAnimatedValue();
                mLockPatternView.invalidate();
            }
        });
        if (finishListener != null) {
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    finishListener.run();
                }
            });

            // Also animate the Emergency call
            mAppearAnimationUtils.createAnimation(mEcaView, delay, duration, startTranslationY,
            interpolator, null);

            // And the forgot pattern button
            if (mForgotPatternButton != null
                    && mForgotPatternButton.getVisibility() == View.VISIBLE) {
                mAppearAnimationUtils.createAnimation(mForgotPatternButton, delay, duration,
                        startTranslationY, interpolator, null);
            }
        }
        animator.start();
        mLockPatternView.invalidate();
    }
}
