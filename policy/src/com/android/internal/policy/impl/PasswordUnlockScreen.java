/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;

import com.android.internal.policy.impl.PatternUnlockScreen.FooterMode;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.PasswordEntryKeyboardView;

import android.os.CountDownTimer;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.text.method.TextKeyListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import com.android.internal.R;
import com.android.internal.widget.PasswordEntryKeyboardHelper;

/**
 * Displays a dialer-like interface or alphanumeric (latin-1) key entry for the user to enter
 * an unlock password
 */
public class PasswordUnlockScreen extends LinearLayout implements KeyguardScreen,
        View.OnClickListener, KeyguardUpdateMonitor.InfoCallback, OnEditorActionListener {

    private static final String TAG = "PasswordUnlockScreen";
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardScreenCallback mCallback;

    private boolean mIsAlpha;

    private EditText mPasswordEntry;
    private Button mEmergencyCallButton;
    private LockPatternUtils mLockPatternUtils;
    private PasswordEntryKeyboardView mKeyboardView;
    private PasswordEntryKeyboardView mKeyboardViewAlpha;
    private PasswordEntryKeyboardHelper mKeyboardHelper;
    private PasswordEntryKeyboardHelper mKeyboardHelperAlpha;

    private int mCreationOrientation;
    private int mCreationHardKeyboardHidden;
    private CountDownTimer mCountdownTimer;

    private StatusView mStatusView;
    private final boolean mUseSystemIME = true; // TODO: Make configurable

    // To avoid accidental lockout due to events while the device in in the pocket, ignore
    // any passwords with length less than or equal to this length.
    private static final int MINIMUM_PASSWORD_LENGTH_BEFORE_REPORT = 3;

    public PasswordUnlockScreen(Context context, Configuration configuration,
            LockPatternUtils lockPatternUtils, KeyguardUpdateMonitor updateMonitor,
            KeyguardScreenCallback callback) {
        super(context);

        mCreationHardKeyboardHidden = configuration.hardKeyboardHidden;
        mCreationOrientation = configuration.orientation;
        mUpdateMonitor = updateMonitor;
        mCallback = callback;
        mLockPatternUtils = lockPatternUtils;

        LayoutInflater layoutInflater = LayoutInflater.from(context);
        if (mCreationOrientation != Configuration.ORIENTATION_LANDSCAPE) {
            layoutInflater.inflate(R.layout.keyguard_screen_password_portrait, this, true);
        } else {
            layoutInflater.inflate(R.layout.keyguard_screen_password_landscape, this, true);
        }

        mStatusView = new StatusView(this, mUpdateMonitor, mLockPatternUtils);

        final int quality = lockPatternUtils.getKeyguardStoredPasswordQuality();
        mIsAlpha = DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC == quality
                || DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC == quality
                || DevicePolicyManager.PASSWORD_QUALITY_COMPLEX == quality;

        // TODO: re-enable on phones with keyboards
        final boolean isPhysicalKbShowing = false;
        mKeyboardView = (PasswordEntryKeyboardView) findViewById(R.id.keyboard);
        mKeyboardViewAlpha = (PasswordEntryKeyboardView) findViewById(R.id.keyboardAlpha);
        mPasswordEntry = (EditText) findViewById(R.id.passwordEntry);
        mPasswordEntry.setOnEditorActionListener(this);
        mPasswordEntry.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (mIsAlpha && !isPhysicalKbShowing && !mUseSystemIME) {
                    // Toggle visibility of alpha keyboard
                    final boolean visible = mKeyboardViewAlpha.getVisibility() == View.VISIBLE;
                    mKeyboardViewAlpha.setVisibility(visible ? View.GONE : View.VISIBLE);
                }
                mCallback.pokeWakelock();
            }
        });

        // We don't currently use the IME for PIN mode, but this will make it work if we ever do...
        if (!mIsAlpha) {
            mPasswordEntry.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        } else {
            mPasswordEntry.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }

        mEmergencyCallButton = (Button) findViewById(R.id.emergencyCall);
        mEmergencyCallButton.setOnClickListener(this);
        mLockPatternUtils.updateEmergencyCallButtonState(mEmergencyCallButton);

        mKeyboardHelper = new PasswordEntryKeyboardHelper(context, mKeyboardView, this, false);
        //mCreationHardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
        if (mKeyboardViewAlpha == null || !mIsAlpha) {
            mKeyboardHelper.setKeyboardMode(mIsAlpha ?
                    PasswordEntryKeyboardHelper.KEYBOARD_MODE_ALPHA
                    : PasswordEntryKeyboardHelper.KEYBOARD_MODE_NUMERIC);
            mKeyboardView.setVisibility(isPhysicalKbShowing ? View.INVISIBLE : View.VISIBLE);
        } else {
            mKeyboardHelperAlpha = new PasswordEntryKeyboardHelper(context, mKeyboardViewAlpha,
                    this, false);
            mKeyboardHelper.setKeyboardMode(PasswordEntryKeyboardHelper.KEYBOARD_MODE_NUMERIC);
            mKeyboardHelperAlpha.setKeyboardMode(PasswordEntryKeyboardHelper.KEYBOARD_MODE_ALPHA);
            mKeyboardView.setVisibility(View.GONE);
            mPasswordEntry.setWidth(mKeyboardViewAlpha.getLayoutParams().width);
        }

        mPasswordEntry.requestFocus();

        // This allows keyboards with overlapping qwerty/numeric keys to choose just the
        // numeric keys.
        if (mIsAlpha) {
            mPasswordEntry.setKeyListener(TextKeyListener.getInstance());
            // mStatusView.setHelpMessage(R.string.keyguard_password_enter_password_code,
            //      StatusView.LOCK_ICON);
        } else {
            mPasswordEntry.setKeyListener(DigitsKeyListener.getInstance());
            //mStatusView.setHelpMessage(R.string.keyguard_password_enter_pin_code,
            //      StatusView.LOCK_ICON);
        }

        mKeyboardHelper.setVibratePattern(mLockPatternUtils.isTactileFeedbackEnabled() ?
                com.android.internal.R.array.config_virtualKeyVibePattern : 0);
        if (mKeyboardHelperAlpha != null) {
            mKeyboardHelperAlpha.setVibratePattern(mLockPatternUtils.isTactileFeedbackEnabled() ?
                    com.android.internal.R.array.config_virtualKeyVibePattern : 0);
        }

        // until we get an update...
        mStatusView.setCarrierText(LockScreen.getCarrierString(
                        mUpdateMonitor.getTelephonyPlmn(),
                        mUpdateMonitor.getTelephonySpn()));

        mUpdateMonitor.registerInfoCallback(this);
        //mUpdateMonitor.registerSimStateCallback(this);

        resetStatusInfo();
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // send focus to the password field
        return mPasswordEntry.requestFocus(direction, previouslyFocusedRect);
    }

    /** {@inheritDoc} */
    public boolean needsInput() {
        return mUseSystemIME && mIsAlpha;
    }

    /** {@inheritDoc} */
    public void onPause() {

    }

    /** {@inheritDoc} */
    public void onResume() {
        // reset status
        mStatusView.resetStatusInfo(mUpdateMonitor, mLockPatternUtils);

        // start fresh
        mPasswordEntry.setText("");
        resetStatusInfo();
        mPasswordEntry.requestFocus();
        mLockPatternUtils.updateEmergencyCallButtonState(mEmergencyCallButton);

        // if the user is currently locked out, enforce it.
        long deadline = mLockPatternUtils.getLockoutAttemptDeadline();
        if (deadline != 0) {
            handleAttemptLockout(deadline);
        }
    }

    /** {@inheritDoc} */
    public void cleanUp() {
        mUpdateMonitor.removeCallback(this);
    }

    public void onClick(View v) {
        if (v == mEmergencyCallButton) {
            mCallback.takeEmergencyCallAction();
        }
        mCallback.pokeWakelock();
    }

    private void verifyPasswordAndUnlock() {
        String entry = mPasswordEntry.getText().toString();
        if (mLockPatternUtils.checkPassword(entry)) {
            mCallback.keyguardDone(true);
            mCallback.reportSuccessfulUnlockAttempt();
            mStatusView.setInstructionText(null);
        } else if (entry.length() > MINIMUM_PASSWORD_LENGTH_BEFORE_REPORT ) {
            // to avoid accidental lockout, only count attempts that are long enough to be a
            // real password. This may require some tweaking.
            mCallback.reportFailedUnlockAttempt();
            if (0 == (mUpdateMonitor.getFailedAttempts()
                    % LockPatternUtils.FAILED_ATTEMPTS_BEFORE_TIMEOUT)) {
                long deadline = mLockPatternUtils.setLockoutAttemptDeadline();
                handleAttemptLockout(deadline);
            }
            mStatusView.setInstructionText(R.string.lockscreen_password_wrong);
        } else if (entry.length() > 0) {
            mStatusView.setInstructionText(R.string.lockscreen_password_wrong);
        }
        mPasswordEntry.setText("");
    }

    // Prevent user from using the PIN/Password entry until scheduled deadline.
    private void handleAttemptLockout(long elapsedRealtimeDeadline) {
        mPasswordEntry.setEnabled(false);
        mKeyboardView.setEnabled(false);
        long elapsedRealtime = SystemClock.elapsedRealtime();
        mCountdownTimer = new CountDownTimer(elapsedRealtimeDeadline - elapsedRealtime, 1000) {

            @Override
            public void onTick(long millisUntilFinished) {
                int secondsRemaining = (int) (millisUntilFinished / 1000);
                String instructions = getContext().getString(
                        R.string.lockscreen_too_many_failed_attempts_countdown,
                        secondsRemaining);
                mStatusView.setInstructionText(instructions);
            }

            @Override
            public void onFinish() {
                mPasswordEntry.setEnabled(true);
                mKeyboardView.setEnabled(true);
                resetStatusInfo();
            }
        }.start();
    }


    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        mCallback.pokeWakelock();
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Configuration config = getResources().getConfiguration();
        if (config.orientation != mCreationOrientation
                || config.hardKeyboardHidden != mCreationHardKeyboardHidden) {
            mCallback.recreateMe(config);
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation != mCreationOrientation
                || newConfig.hardKeyboardHidden != mCreationHardKeyboardHidden) {
            mCallback.recreateMe(newConfig);
        }
    }

    public void onKeyboardChange(boolean isKeyboardOpen) {
        // Don't show the soft keyboard when the real keyboard is open
        mKeyboardView.setVisibility(isKeyboardOpen ? View.INVISIBLE : View.VISIBLE);
    }

    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        // Check if this was the result of hitting the enter key
        if (actionId == EditorInfo.IME_NULL || actionId == EditorInfo.IME_ACTION_DONE) {
            verifyPasswordAndUnlock();
            return true;
        }
        return false;
    }

    // ---------- InfoCallback

    /** {@inheritDoc} */
    public void onRefreshBatteryInfo(boolean showBatteryInfo, boolean pluggedIn, int batteryLevel) {
        mStatusView.onRefreshBatteryInfo(showBatteryInfo, pluggedIn, batteryLevel);
    }

    /** {@inheritDoc} */
    public void onTimeChanged() {
        mStatusView.onTimeChanged();
    }

    /** {@inheritDoc} */
    public void onRefreshCarrierInfo(CharSequence plmn, CharSequence spn) {
        mStatusView.onRefreshCarrierInfo(plmn, spn);
    }

    /** {@inheritDoc} */
    public void onRingerModeChanged(int state) {
        // not currently used
    }

    // ---------- SimStateCallback

    /** {@inheritDoc} */
    public void onPhoneStateChanged(String newState) {
        mLockPatternUtils.updateEmergencyCallButtonState(mEmergencyCallButton);
    }

    private void resetStatusInfo() {
        mStatusView.setInstructionText(null);
        mStatusView.updateStatusLines(true);
    }

}
