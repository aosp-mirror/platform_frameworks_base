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
import android.content.ServiceConnection;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.internal.R;
import com.android.internal.widget.LinearLayoutWithDefaultTouchRecepient;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;

import java.util.List;

/**
 * This is the screen that shows the 9 circle unlock widget and instructs
 * the user how to unlock their device, or make an emergency call.
 */
class UnlockScreen extends LinearLayoutWithDefaultTouchRecepient
        implements KeyguardScreen, KeyguardUpdateMonitor.ConfigurationChangeCallback {

    private static final String TAG = "UnlockScreen";

    // how long before we clear the wrong pattern
    private static final int PATTERN_CLEAR_TIMEOUT_MS = 2000;

    // how long we stay awake once the user is ready to enter a pattern
    private static final int UNLOCK_PATTERN_WAKE_INTERVAL_MS = 7000;

    private int mFailedPatternAttemptsSinceLastTimeout = 0;
    private int mTotalFailedPatternAttempts = 0;
    private CountDownTimer mCountdownTimer = null;

    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardScreenCallback mCallback;

    private boolean mCreatedInPortrait;

    private ImageView mUnlockIcon;
    private TextView mUnlockHeader;
    private LockPatternView mLockPatternView;

    private ViewGroup mFooterNormal;
    private ViewGroup mFooterForgotPattern;

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

    private Button mForgotPatternButton;

    private ServiceConnection mServiceConnection;


    enum FooterMode {
        Normal,
        ForgotLockPattern,
        VerifyUnlocked
    }

    private void updateFooter(FooterMode mode) {
        switch (mode) {
            case Normal:
                mFooterNormal.setVisibility(View.VISIBLE);
                mFooterForgotPattern.setVisibility(View.GONE);
                break;
            case ForgotLockPattern:
                mFooterNormal.setVisibility(View.GONE);
                mFooterForgotPattern.setVisibility(View.VISIBLE);
                break;
            case VerifyUnlocked:
                mFooterNormal.setVisibility(View.GONE);
                mFooterForgotPattern.setVisibility(View.GONE);
        }
    }

    /**
     * @param context The context.
     * @param lockPatternUtils Used to lookup lock pattern settings.
     * @param updateMonitor Used to lookup state affecting keyguard.
     * @param callback Used to notify the manager when we're done, etc.
     * @param totalFailedAttempts The current number of failed attempts.
     */
    UnlockScreen(Context context,
            LockPatternUtils lockPatternUtils,
            KeyguardUpdateMonitor updateMonitor,
            KeyguardScreenCallback callback,
            int totalFailedAttempts) {
        super(context);
        mLockPatternUtils = lockPatternUtils;
        mUpdateMonitor = updateMonitor;
        mCallback = callback;
        mTotalFailedPatternAttempts = totalFailedAttempts;
        mFailedPatternAttemptsSinceLastTimeout = totalFailedAttempts % LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT;

        if (mUpdateMonitor.isInPortrait()) {
            LayoutInflater.from(context).inflate(R.layout.keyguard_screen_unlock_portrait, this, true);
        } else {
            LayoutInflater.from(context).inflate(R.layout.keyguard_screen_unlock_landscape, this, true);
        }

        mUnlockIcon = (ImageView) findViewById(R.id.unlockLockIcon);

        mLockPatternView = (LockPatternView) findViewById(R.id.lockPattern);
        mUnlockHeader = (TextView) findViewById(R.id.headerText);

        mUnlockHeader.setText(R.string.lockscreen_pattern_instructions);

        mFooterNormal = (ViewGroup) findViewById(R.id.footerNormal);
        mFooterForgotPattern = (ViewGroup) findViewById(R.id.footerForgotPattern);

        // emergency call buttons
        final OnClickListener emergencyClick = new OnClickListener() {
            public void onClick(View v) {
                mCallback.takeEmergencyCallAction();
            }
        };
        Button emergencyAlone = (Button) findViewById(R.id.emergencyCallAlone);
        emergencyAlone.setFocusable(false); // touch only!
        emergencyAlone.setOnClickListener(emergencyClick);
        Button emergencyTogether = (Button) findViewById(R.id.emergencyCallTogether);
        emergencyTogether.setFocusable(false);
        emergencyTogether.setOnClickListener(emergencyClick);

        mForgotPatternButton = (Button) findViewById(R.id.forgotPattern);
        mForgotPatternButton.setText(R.string.lockscreen_forgot_pattern_button_text);
        mForgotPatternButton.setOnClickListener(new OnClickListener() {

            public void onClick(View v) {
                mLockPatternUtils.setPermanentlyLocked(true);
                mCallback.goToUnlockScreen();
            }
        });

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

        mCreatedInPortrait = updateMonitor.isInPortrait();
        updateMonitor.registerConfigurationChangeCallback(this);
        setFocusableInTouchMode(true);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mCallback.goToLockScreen();
            return true;
        }
        return false;        
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
            mCallback.pokeWakelock(UNLOCK_PATTERN_WAKE_INTERVAL_MS);
        }
        return result;
    }

    /** {@inheritDoc} */
    public void onOrientationChange(boolean inPortrait) {
        if (inPortrait != mCreatedInPortrait) {
            mCallback.recreateMe();
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
    }

    /** {@inheritDoc} */
    public void onResume() {
        // reset header
        mUnlockHeader.setText(R.string.lockscreen_pattern_instructions);
        mUnlockIcon.setVisibility(View.VISIBLE);

        // reset lock pattern
        mLockPatternView.enableInput();
        mLockPatternView.setEnabled(true);
        mLockPatternView.clearPattern();
        
        // show "forgot pattern?" button if we have an alternate authentication method
        mForgotPatternButton.setVisibility(mCallback.doesFallbackUnlockScreenExist() 
                ? View.VISIBLE : View.INVISIBLE);

        // if the user is currently locked out, enforce it.
        long deadline = mLockPatternUtils.getLockoutAttemptDeadline();
        if (deadline != 0) {
            handleAttemptLockout(deadline);
        }

        // the footer depends on how many total attempts the user has failed
        if (mCallback.isVerifyUnlockOnly()) {
            updateFooter(FooterMode.VerifyUnlocked);
        } else if (mTotalFailedPatternAttempts < LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT) {
            updateFooter(FooterMode.Normal);
        } else {
            updateFooter(FooterMode.ForgotLockPattern);
        }
    }

    /** {@inheritDoc} */
    public void cleanUp() {
        mUpdateMonitor.removeCallback(this);
    }

    private class UnlockPatternListener
            implements LockPatternView.OnPatternListener {

        public void onPatternStart() {
            mLockPatternView.removeCallbacks(mCancelPatternRunnable);
        }

        public void onPatternCleared() {
        }

        public void onPatternDetected(List<LockPatternView.Cell> pattern) {
            if (mLockPatternUtils.checkPattern(pattern)) {
                mLockPatternView
                        .setDisplayMode(LockPatternView.DisplayMode.Correct);
                mUnlockIcon.setVisibility(View.GONE);
                mUnlockHeader.setText("");
                mCallback.keyguardDone(true);
            } else {
                mCallback.pokeWakelock(UNLOCK_PATTERN_WAKE_INTERVAL_MS);
                mLockPatternView.setDisplayMode(LockPatternView.DisplayMode.Wrong);
                if (pattern.size() >= LockPatternUtils.MIN_PATTERN_REGISTER_FAIL) {
                    mTotalFailedPatternAttempts++;
                    mFailedPatternAttemptsSinceLastTimeout++;
                    mCallback.reportFailedPatternAttempt();
                }
                if (mFailedPatternAttemptsSinceLastTimeout >= LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT) {
                    long deadline = mLockPatternUtils.setLockoutAttemptDeadline();
                    handleAttemptLockout(deadline);
                    return;
                }
                mUnlockIcon.setVisibility(View.VISIBLE);
                mUnlockHeader.setText(R.string.lockscreen_pattern_wrong);
                mLockPatternView.postDelayed(
                        mCancelPatternRunnable,
                        PATTERN_CLEAR_TIMEOUT_MS);
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
                mUnlockHeader.setText(getContext().getString(
                        R.string.lockscreen_too_many_failed_attempts_countdown,
                        secondsRemaining));
            }

            @Override
            public void onFinish() {
                mLockPatternView.setEnabled(true);
                mUnlockHeader.setText(R.string.lockscreen_pattern_instructions);
                mUnlockIcon.setVisibility(View.VISIBLE);
                mFailedPatternAttemptsSinceLastTimeout = 0;
                updateFooter(FooterMode.ForgotLockPattern);
            }
        }.start();
    }

}
