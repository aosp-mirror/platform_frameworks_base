/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.policy.impl;

import android.content.Context;
import android.content.res.Configuration;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.security.KeyStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.MotionEvent;
import android.widget.Button;
import android.util.Log;
import com.android.internal.R;
import com.android.internal.widget.LinearLayoutWithDefaultTouchRecepient;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;
import com.android.internal.widget.LockPatternView.Cell;

import java.util.List;

/**
 * This is the screen that shows the 9 circle unlock widget and instructs
 * the user how to unlock their device, or make an emergency call.
 */
class PatternUnlockScreen extends LinearLayoutWithDefaultTouchRecepient
        implements KeyguardScreen {

    private static final boolean DEBUG = false;
    private static final String TAG = "UnlockScreen";

    // how long before we clear the wrong pattern
    private static final int PATTERN_CLEAR_TIMEOUT_MS = 2000;

    // how long we stay awake after each key beyond MIN_PATTERN_BEFORE_POKE_WAKELOCK
    private static final int UNLOCK_PATTERN_WAKE_INTERVAL_MS = 7000;

    // how long we stay awake after the user hits the first dot.
    private static final int UNLOCK_PATTERN_WAKE_INTERVAL_FIRST_DOTS_MS = 2000;

    // how many cells the user has to cross before we poke the wakelock
    private static final int MIN_PATTERN_BEFORE_POKE_WAKELOCK = 2;

    private int mFailedPatternAttemptsSinceLastTimeout = 0;
    private int mTotalFailedPatternAttempts = 0;
    private CountDownTimer mCountdownTimer = null;

    private LockPatternUtils mLockPatternUtils;
    private KeyguardUpdateMonitor mUpdateMonitor;
    private KeyguardScreenCallback mCallback;

    /**
     * whether there is a fallback option available when the pattern is forgotten.
     */
    private boolean mEnableFallback;

    private KeyguardStatusViewManager mKeyguardStatusViewManager;
    private LockPatternView mLockPatternView;

    /**
     * Keeps track of the last time we poked the wake lock during dispatching
     * of the touch event, initalized to something gauranteed to make us
     * poke it when the user starts drawing the pattern.
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

    private final OnClickListener mForgotPatternClick = new OnClickListener() {
        public void onClick(View v) {
            mCallback.forgotPattern(true);
        }
    };

    private Button mForgotPatternButton;
    private int mCreationOrientation;

    enum FooterMode {
        Normal,
        ForgotLockPattern,
        VerifyUnlocked
    }

    private void hideForgotPatternButton() {
        mForgotPatternButton.setVisibility(View.GONE);
    }

    private void showForgotPatternButton() {
        mForgotPatternButton.setVisibility(View.VISIBLE);
    }

    private void updateFooter(FooterMode mode) {
        switch (mode) {
            case Normal:
                if (DEBUG) Log.d(TAG, "mode normal");
                hideForgotPatternButton();
                break;
            case ForgotLockPattern:
                if (DEBUG) Log.d(TAG, "mode ForgotLockPattern");
                showForgotPatternButton();
                break;
            case VerifyUnlocked:
                if (DEBUG) Log.d(TAG, "mode VerifyUnlocked");
                hideForgotPatternButton();
        }
    }

    /**
     * @param context The context.
     * @param configuration
     * @param lockPatternUtils Used to lookup lock pattern settings.
     * @param updateMonitor Used to lookup state affecting keyguard.
     * @param callback Used to notify the manager when we're done, etc.
     * @param totalFailedAttempts The current number of failed attempts.
     * @param enableFallback True if a backup unlock option is available when the user has forgotten
     *        their pattern (e.g they have a google account so we can show them the account based
     *        backup option).
     */
    PatternUnlockScreen(Context context,
                 Configuration configuration, LockPatternUtils lockPatternUtils,
                 KeyguardUpdateMonitor updateMonitor,
                 KeyguardScreenCallback callback,
                 int totalFailedAttempts) {
        super(context);
        mLockPatternUtils = lockPatternUtils;
        mUpdateMonitor = updateMonitor;
        mCallback = callback;
        mTotalFailedPatternAttempts = totalFailedAttempts;
        mFailedPatternAttemptsSinceLastTimeout =
            totalFailedAttempts % LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;

        if (DEBUG) Log.d(TAG,
            "UnlockScreen() ctor: totalFailedAttempts="
                 + totalFailedAttempts + ", mFailedPat...="
                 + mFailedPatternAttemptsSinceLastTimeout
                 );

        mCreationOrientation = configuration.orientation;

        LayoutInflater inflater = LayoutInflater.from(context);

        if (mCreationOrientation != Configuration.ORIENTATION_LANDSCAPE) {
            Log.d(TAG, "portrait mode");
            inflater.inflate(R.layout.keyguard_screen_unlock_portrait, this, true);
        } else {
            Log.d(TAG, "landscape mode");
            inflater.inflate(R.layout.keyguard_screen_unlock_landscape, this, true);
        }

        mKeyguardStatusViewManager = new KeyguardStatusViewManager(this, mUpdateMonitor,
                mLockPatternUtils, mCallback);

        mLockPatternView = (LockPatternView) findViewById(R.id.lockPattern);

        mForgotPatternButton = (Button) findViewById(R.id.forgotPatternButton);
        mForgotPatternButton.setText(R.string.lockscreen_forgot_pattern_button_text);
        mForgotPatternButton.setOnClickListener(mForgotPatternClick);

        // make it so unhandled touch events within the unlock screen go to the
        // lock pattern view.
        setDefaultTouchRecepient(mLockPatternView);

        mLockPatternView.setSaveEnabled(false);
        mLockPatternView.setFocusable(false);
        mLockPatternView.setOnPatternListener(new UnlockPatternListener());

        // stealth mode will be the same for the life of this screen
        mLockPatternView.setInStealthMode(!mLockPatternUtils.isVisiblePatternEnabled());

        // vibrate mode will be the same for the life of this screen
        mLockPatternView.setTactileFeedbackEnabled(mLockPatternUtils.isTactileFeedbackEnabled());

        // assume normal footer mode for now
        updateFooter(FooterMode.Normal);

        setFocusableInTouchMode(true);
    }

    // TODO(bcolonna): This is to tell FaceLock where to draw...but this covers up the wireless
    // service text, so we will want to change the way the area is specified
    public View getUnlockAreaView() {
        return mLockPatternView;
    }

    public void setEnableFallback(boolean state) {
        if (DEBUG) Log.d(TAG, "setEnableFallback(" + state + ")");
        mEnableFallback = state;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // as long as the user is entering a pattern (i.e sending a touch
        // event that was handled by this screen), keep poking the
        // wake lock so that the screen will stay on.
        final boolean result = super.dispatchTouchEvent(ev);
        if (result &&
                ((SystemClock.elapsedRealtime() - mLastPokeTime)
                        >  (UNLOCK_PATTERN_WAKE_INTERVAL_MS - 100))) {
            mLastPokeTime = SystemClock.elapsedRealtime();
        }
        return result;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (LockPatternKeyguardView.DEBUG_CONFIGURATION) {
            Log.v(TAG, "***** PATTERN ATTACHED TO WINDOW");
            Log.v(TAG, "Cur orient=" + mCreationOrientation
                    + ", new config=" + getResources().getConfiguration());
        }
        if (getResources().getConfiguration().orientation != mCreationOrientation) {
            mCallback.recreateMe(getResources().getConfiguration());
        }
    }


    /** {@inheritDoc} */
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (LockPatternKeyguardView.DEBUG_CONFIGURATION) {
            Log.v(TAG, "***** PATTERN CONFIGURATION CHANGED");
            Log.v(TAG, "Cur orient=" + mCreationOrientation
                    + ", new config=" + getResources().getConfiguration());
        }
        if (newConfig.orientation != mCreationOrientation) {
            mCallback.recreateMe(newConfig);
        }
    }

    /** {@inheritDoc} */
    public void onKeyboardChange(boolean isKeyboardOpen) {}

    /** {@inheritDoc} */
    public boolean needsInput() {
        return false;
    }

    /** {@inheritDoc} */
    public void onPause() {
        if (mCountdownTimer != null) {
            mCountdownTimer.cancel();
            mCountdownTimer = null;
        }
        mKeyguardStatusViewManager.onPause();
    }

    /** {@inheritDoc} */
    public void onResume() {
        // reset status
        mKeyguardStatusViewManager.onResume();

        // reset lock pattern
        mLockPatternView.enableInput();
        mLockPatternView.setEnabled(true);
        mLockPatternView.clearPattern();

        // show "forgot pattern?" button if we have an alternate authentication method
        if (mCallback.doesFallbackUnlockScreenExist()) {
            showForgotPatternButton();
        } else {
            hideForgotPatternButton();
        }

        // if the user is currently locked out, enforce it.
        long deadline = mLockPatternUtils.getLockoutAttemptDeadline();
        if (deadline != 0) {
            handleAttemptLockout(deadline);
        }

        // the footer depends on how many total attempts the user has failed
        if (mCallback.isVerifyUnlockOnly()) {
            updateFooter(FooterMode.VerifyUnlocked);
        } else if (mEnableFallback &&
                (mTotalFailedPatternAttempts >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT)) {
            updateFooter(FooterMode.ForgotLockPattern);
        } else {
            updateFooter(FooterMode.Normal);
        }

    }

    /** {@inheritDoc} */
    public void cleanUp() {
        if (DEBUG) Log.v(TAG, "Cleanup() called on " + this);
        mUpdateMonitor.removeCallback(this);
        mLockPatternUtils = null;
        mUpdateMonitor = null;
        mCallback = null;
        mLockPatternView.setOnPatternListener(null);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) {
            // when timeout dialog closes we want to update our state
            onResume();
        }
    }

    private class UnlockPatternListener
            implements LockPatternView.OnPatternListener {

        public void onPatternStart() {
            mLockPatternView.removeCallbacks(mCancelPatternRunnable);
        }

        public void onPatternCleared() {
        }

        public void onPatternCellAdded(List<Cell> pattern) {
            // To guard against accidental poking of the wakelock, look for
            // the user actually trying to draw a pattern of some minimal length.
            if (pattern.size() > MIN_PATTERN_BEFORE_POKE_WAKELOCK) {
                mCallback.pokeWakelock(UNLOCK_PATTERN_WAKE_INTERVAL_MS);
            } else {
                // Give just a little extra time if they hit one of the first few dots
                mCallback.pokeWakelock(UNLOCK_PATTERN_WAKE_INTERVAL_FIRST_DOTS_MS);
            }
        }

        public void onPatternDetected(List<LockPatternView.Cell> pattern) {
            if (mLockPatternUtils.checkPattern(pattern)) {
                mLockPatternView
                        .setDisplayMode(LockPatternView.DisplayMode.Correct);
                mKeyguardStatusViewManager.setInstructionText("");
                mKeyguardStatusViewManager.updateStatusLines(true);
                mCallback.keyguardDone(true);
                mCallback.reportSuccessfulUnlockAttempt();
                KeyStore.getInstance().password(LockPatternUtils.patternToString(pattern));
            } else {
                boolean reportFailedAttempt = false;
                if (pattern.size() > MIN_PATTERN_BEFORE_POKE_WAKELOCK) {
                    mCallback.pokeWakelock(UNLOCK_PATTERN_WAKE_INTERVAL_MS);
                }
                mLockPatternView.setDisplayMode(LockPatternView.DisplayMode.Wrong);
                if (pattern.size() >= LockPatternUtils.MIN_PATTERN_REGISTER_FAIL) {
                    mTotalFailedPatternAttempts++;
                    mFailedPatternAttemptsSinceLastTimeout++;
                    reportFailedAttempt = true;
                }
                if (mFailedPatternAttemptsSinceLastTimeout
                        >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT) {
                    long deadline = mLockPatternUtils.setLockoutAttemptDeadline();
                    handleAttemptLockout(deadline);
                } else {
                    // TODO mUnlockIcon.setVisibility(View.VISIBLE);
                    mKeyguardStatusViewManager.setInstructionText(
                            getContext().getString(R.string.lockscreen_pattern_wrong));
                    mKeyguardStatusViewManager.updateStatusLines(true);
                    mLockPatternView.postDelayed(
                            mCancelPatternRunnable,
                            PATTERN_CLEAR_TIMEOUT_MS);
                }

                // Because the following can result in cleanUp() being called on this screen,
                // member variables reset in cleanUp() shouldn't be accessed after this call.
                if (reportFailedAttempt) {
                    mCallback.reportFailedUnlockAttempt();
                }
            }
        }
    }

    private void handleAttemptLockout(long elapsedRealtimeDeadline) {
        mLockPatternView.clearPattern();
        mLockPatternView.setEnabled(false);
        long elapsedRealtime = SystemClock.elapsedRealtime();
        mCountdownTimer = new CountDownTimer(elapsedRealtimeDeadline - elapsedRealtime, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                int secondsRemaining = (int) (millisUntilFinished / 1000);
                mKeyguardStatusViewManager.setInstructionText(getContext().getString(
                        R.string.lockscreen_too_many_failed_attempts_countdown,
                        secondsRemaining));
                mKeyguardStatusViewManager.updateStatusLines(true);
            }

            @Override
            public void onFinish() {
                mLockPatternView.setEnabled(true);
                mKeyguardStatusViewManager.setInstructionText(getContext().getString(
                        R.string.lockscreen_pattern_instructions));
                mKeyguardStatusViewManager.updateStatusLines(true);
                // TODO mUnlockIcon.setVisibility(View.VISIBLE);
                mFailedPatternAttemptsSinceLastTimeout = 0;
                if (mEnableFallback) {
                    updateFooter(FooterMode.ForgotLockPattern);
                } else {
                    updateFooter(FooterMode.Normal);
                }
            }
        }.start();
    }

}
