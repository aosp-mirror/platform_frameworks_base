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

import com.android.internal.telephony.IccCard.State;
import com.android.internal.widget.LockPatternUtils;

import android.text.Editable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.internal.R;

/**
 * Displays a dialer-like interface or alphanumeric (latin-1) key entry for the user to enter
 * an unlock password
 */
public class PasswordUnlockScreen extends LinearLayout implements KeyguardScreen, View.OnClickListener,
        KeyguardUpdateMonitor.ConfigurationChangeCallback, KeyguardUpdateMonitor.InfoCallback {

    private static final int DIGIT_PRESS_WAKE_MILLIS = 5000;

    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardScreenCallback mCallback;

    private final boolean mCreatedWithKeyboardOpen;

    private TextView mPasswordTextView;
    private TextView mOkButton;
    private TextView mEmergencyCallButton;
    private View mBackSpaceButton;
    private TextView mCarrier;
    private LockPatternUtils mLockPatternUtils;
    private Button mCancelButton;
    private int mPasswordAttempts = 0;
    private int mMinimumPasswordLength = 4; // TODO: get from policy store

    private static final char[] DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};

    public PasswordUnlockScreen(Context context, LockPatternUtils lockPatternUtils,
            KeyguardUpdateMonitor updateMonitor, KeyguardScreenCallback callback) {
        super(context);
        mUpdateMonitor = updateMonitor;
        mCallback = callback;
        mCreatedWithKeyboardOpen = mUpdateMonitor.isKeyboardOpen();

        LayoutInflater layoutInflater = LayoutInflater.from(context);
        if (mCreatedWithKeyboardOpen) {
            layoutInflater.inflate(R.layout.keyguard_screen_password_landscape, this, true);
        } else {
            layoutInflater.inflate(R.layout.keyguard_screen_password_portrait, this, true);
            new TouchInput();
        }

        mPasswordTextView = (TextView) findViewById(R.id.pinDisplay);
        mBackSpaceButton = findViewById(R.id.backspace);
        mBackSpaceButton.setOnClickListener(this);

        // The cancel button is not used on this screen.
        mCancelButton = (Button) findViewById(R.id.cancel);
        if (mCancelButton != null) {
            mCancelButton.setText("");
        }

        mEmergencyCallButton = (TextView) findViewById(R.id.emergencyCall);
        mOkButton = (TextView) findViewById(R.id.ok);

        mPasswordTextView.setFocusable(false);

        mEmergencyCallButton.setOnClickListener(this);
        mOkButton.setOnClickListener(this);

        mUpdateMonitor.registerConfigurationChangeCallback(this);

        mLockPatternUtils = lockPatternUtils;
        mCarrier = (TextView) findViewById(R.id.carrier);
        // until we get an update...
        mCarrier.setText(LockScreen.getCarrierString(mUpdateMonitor.getTelephonyPlmn(),
                        mUpdateMonitor.getTelephonySpn()));

        updateMonitor.registerInfoCallback(this);
        updateMonitor.registerConfigurationChangeCallback(this);

        setFocusableInTouchMode(true);
    }

    /** {@inheritDoc} */
    public boolean needsInput() {
        return true;
    }

    /** {@inheritDoc} */
    public void onPause() {

    }

    /** {@inheritDoc} */
    public void onResume() {
        // start fresh
        mPasswordTextView.setText("");
    }

    /** {@inheritDoc} */
    public void cleanUp() {
        mUpdateMonitor.removeCallback(this);
    }

    public void onClick(View v) {
        if (v == mBackSpaceButton) {
            final Editable digits = mPasswordTextView.getEditableText();
            final int len = digits.length();
            if (len > 0) {
                digits.delete(len-1, len);
            }
        } else if (v == mEmergencyCallButton) {
            mCallback.takeEmergencyCallAction();
        } else if (v == mOkButton) {
            verifyPasswordAndUnlock();
        }
        mCallback.pokeWakelock();
    }

    private void verifyPasswordAndUnlock() {
        String entry = mPasswordTextView.getText().toString();
        if (mLockPatternUtils.checkPassword(entry)) {
            mPasswordAttempts = 0;
            mCallback.keyguardDone(true);
        } else if (entry.length() >= mMinimumPasswordLength ) {
            // to avoid accidental lockout, only count attempts that are long enough to be a
            // real password. This may require some tweaking.
            mPasswordAttempts++;
        }
        mPasswordTextView.setText("");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }

        final char match = event.getMatch(DIGITS);
        if (match != 0) {
            reportDigit(match - '0');
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            mPasswordTextView.onKeyDown(keyCode, event);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            verifyPasswordAndUnlock();
            return true;
        }

        return false;
    }

    private void reportDigit(int digit) {
        mPasswordTextView.append(Integer.toString(digit));
    }

    public void onOrientationChange(boolean inPortrait) {

    }

    public void onKeyboardChange(boolean isKeyboardOpen) {
        if (isKeyboardOpen != mCreatedWithKeyboardOpen) {
            mCallback.recreateMe();
        }
    }

    /**
     * Helper class to handle input from touch dialer.  Only relevant when
     * the keyboard is shut.
     */
    private class TouchInput implements View.OnClickListener {
        private int mDigitIds[] = { R.id.zero, R.id.one, R.id.two, R.id.three, R.id.four,
                R.id.five, R.id.six, R.id.seven, R.id.eight, R.id.nine };
        private TextView mCancelButton;
        private TouchInput() {
            for (int i = 0; i < mDigitIds.length; i++) {
                Button button = (Button) findViewById(mDigitIds[i]);
                button.setOnClickListener(this);
                button.setText(Integer.toString(i));
            }
            mCancelButton = (TextView) findViewById(R.id.cancel);
            mCancelButton.setOnClickListener(this);
            mOkButton = (TextView) findViewById(R.id.ok);
            mOkButton.setOnClickListener(this);
        }

        public void onClick(View v) {
            if (v == mCancelButton) {
                return;
            }
            if (v == mOkButton) {
                verifyPasswordAndUnlock();
            }

            final int digit = checkDigit(v);
            if (digit >= 0) {
                mCallback.pokeWakelock(DIGIT_PRESS_WAKE_MILLIS);
                reportDigit(digit);
            }
        }

        private int checkDigit(View v) {
            int digit = -1;
            for (int i = 0; i < mDigitIds.length; i++) {
                if (v.getId() == mDigitIds[i]) {
                    digit = i;
                    break;
                }
            }
            return digit;
        }
    }

    /** {@inheritDoc} */
    public void onRefreshCarrierInfo(CharSequence plmn, CharSequence spn) {
        mCarrier.setText(LockScreen.getCarrierString(plmn, spn));
    }

    public void onRefreshBatteryInfo(boolean showBatteryInfo, boolean pluggedIn, int batteryLevel) {

    }

    public void onRingerModeChanged(int state) {

    }

    public void onTimeChanged() {

    }

    public void onSimStateChanged(State simState) {

    }
}
