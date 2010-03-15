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

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.PasswordEntryKeyboardView;

import android.telephony.TelephonyManager;
import android.text.method.DigitsKeyListener;
import android.text.method.TextKeyListener;
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

    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardScreenCallback mCallback;

    private EditText mPasswordEntry;
    private Button mEmergencyCallButton;
    private LockPatternUtils mLockPatternUtils;
    private PasswordEntryKeyboardView mKeyboardView;
    private PasswordEntryKeyboardHelper mKeyboardHelper;

    private int mCreationOrientation;
    private int mKeyboardHidden;

    // To avoid accidental lockout due to events while the device in in the pocket, ignore
    // any passwords with length less than or equal to this length.
    private static final int MINIMUM_PASSWORD_LENGTH_BEFORE_REPORT = 3;

    public PasswordUnlockScreen(Context context, Configuration configuration,
            LockPatternUtils lockPatternUtils, KeyguardUpdateMonitor updateMonitor,
            KeyguardScreenCallback callback) {
        super(context);

        mKeyboardHidden = configuration.hardKeyboardHidden;
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

        boolean isAlpha = lockPatternUtils.getPasswordMode() == LockPatternUtils.MODE_PASSWORD;
        mKeyboardView = (PasswordEntryKeyboardView) findViewById(R.id.keyboard);
        mPasswordEntry = (EditText) findViewById(R.id.passwordEntry);
        mPasswordEntry.setOnEditorActionListener(this);
        mEmergencyCallButton = (Button) findViewById(R.id.emergencyCall);
        mEmergencyCallButton.setOnClickListener(this);
        mLockPatternUtils.updateEmergencyCallButtonState(mEmergencyCallButton);

        mKeyboardHelper = new PasswordEntryKeyboardHelper(context, mKeyboardView, this);
        mKeyboardHelper.setKeyboardMode(isAlpha ? PasswordEntryKeyboardHelper.KEYBOARD_MODE_ALPHA
                : PasswordEntryKeyboardHelper.KEYBOARD_MODE_NUMERIC);

        mKeyboardView.setVisibility(mKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
                ? View.INVISIBLE : View.VISIBLE);
        mPasswordEntry.requestFocus();

        // This allows keyboards with overlapping qwerty/numeric keys to choose just the
        // numeric keys.
        if (isAlpha) {
            mPasswordEntry.setKeyListener(TextKeyListener.getInstance());
        } else {
            mPasswordEntry.setKeyListener(DigitsKeyListener.getInstance());
        }

        mKeyboardHelper.setVibratePattern(mLockPatternUtils.isTactileFeedbackEnabled() ?
                com.android.internal.R.array.config_virtualKeyVibePattern : 0);
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        // send focus to the password field
        return mPasswordEntry.requestFocus(direction, previouslyFocusedRect);
    }

    /** {@inheritDoc} */
    public boolean needsInput() {
        return false;
    }

    /** {@inheritDoc} */
    public void onPause() {

    }

    /** {@inheritDoc} */
    public void onResume() {
        // start fresh
        mPasswordEntry.setText("");
        mPasswordEntry.requestFocus();
        mLockPatternUtils.updateEmergencyCallButtonState(mEmergencyCallButton);
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
        } else if (entry.length() > MINIMUM_PASSWORD_LENGTH_BEFORE_REPORT ) {
            // to avoid accidental lockout, only count attempts that are long enough to be a
            // real password. This may require some tweaking.
            mCallback.reportFailedUnlockAttempt();
        }
        mPasswordEntry.setText("");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        mCallback.pokeWakelock();
        return false;
    }

    /** {@inheritDoc} */
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation != mCreationOrientation) {
            mCallback.recreateMe(newConfig);
        }
    }

    public void onKeyboardChange(boolean isKeyboardOpen) {
        // Don't show the soft keyboard when the real keyboard is open
        mKeyboardView.setVisibility(isKeyboardOpen ? View.INVISIBLE : View.VISIBLE);
    }

    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        // Check if this was the result of hitting the enter key
        if (actionId == EditorInfo.IME_NULL) {
            verifyPasswordAndUnlock();
            return true;
        }
        return false;
    }

    public void onPhoneStateChanged(String newState) {
        mLockPatternUtils.updateEmergencyCallButtonState(mEmergencyCallButton);
    }

    public void onRefreshBatteryInfo(boolean showBatteryInfo, boolean pluggedIn, int batteryLevel) {

    }

    public void onRefreshCarrierInfo(CharSequence plmn, CharSequence spn) {

    }

    public void onRingerModeChanged(int state) {

    }

    public void onTimeChanged() {

    }

}
