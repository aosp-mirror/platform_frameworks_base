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

package com.android.internal.policy.impl.keyguard_obsolete;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.telephony.ITelephony;
import com.android.internal.widget.LockPatternUtils;

import android.text.Editable;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.internal.R;

/**
 * Displays a dialer like interface to unlock the SIM PUK.
 */
public class SimPukUnlockScreen extends LinearLayout implements KeyguardScreen,
        View.OnClickListener, View.OnFocusChangeListener {

    private static final int DIGIT_PRESS_WAKE_MILLIS = 5000;

    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardScreenCallback mCallback;
    private KeyguardStatusViewManager mKeyguardStatusViewManager;

    private TextView mHeaderText;
    private TextView mPukText;
    private TextView mPinText;
    private TextView mFocusedEntry;

    private View mOkButton;
    private View mDelPukButton;
    private View mDelPinButton;

    private ProgressDialog mSimUnlockProgressDialog = null;

    private LockPatternUtils mLockPatternUtils;

    private int mCreationOrientation;

    private int mKeyboardHidden;

    private static final char[] DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};

    public SimPukUnlockScreen(Context context, Configuration configuration,
            KeyguardUpdateMonitor updateMonitor, KeyguardScreenCallback callback,
            LockPatternUtils lockpatternutils) {
        super(context);
        mUpdateMonitor = updateMonitor;
        mCallback = callback;;

        mCreationOrientation = configuration.orientation;
        mKeyboardHidden = configuration.hardKeyboardHidden;
        mLockPatternUtils = lockpatternutils;

        LayoutInflater inflater = LayoutInflater.from(context);
        if (mKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
            inflater.inflate(
                    R.layout.keyguard_screen_sim_puk_landscape, this, true);
        } else {
            inflater.inflate(
                    R.layout.keyguard_screen_sim_puk_portrait, this, true);
            new TouchInput();
        }

        mHeaderText = (TextView) findViewById(R.id.headerText);

        mPukText = (TextView) findViewById(R.id.pukDisplay);
        mPinText = (TextView) findViewById(R.id.pinDisplay);
        mDelPukButton = findViewById(R.id.pukDel);
        mDelPinButton = findViewById(R.id.pinDel);
        mOkButton = findViewById(R.id.ok);

        mDelPinButton.setOnClickListener(this);
        mDelPukButton.setOnClickListener(this);
        mOkButton.setOnClickListener(this);

        mHeaderText.setText(R.string.keyguard_password_enter_puk_code);
        // To make marquee work
        mHeaderText.setSelected(true);

        mKeyguardStatusViewManager = new KeyguardStatusViewManager(this, updateMonitor,
                lockpatternutils, callback, true);

        mPinText.setFocusableInTouchMode(true);
        mPinText.setOnFocusChangeListener(this);
        mPukText.setFocusableInTouchMode(true);
        mPukText.setOnFocusChangeListener(this);
    }

    /** {@inheritDoc} */
    public boolean needsInput() {
        return false;
    }

    /** {@inheritDoc} */
    public void onPause() {
        mKeyguardStatusViewManager.onPause();
    }

    /** {@inheritDoc} */
    public void onResume() {
        // start fresh
        mHeaderText.setText(R.string.keyguard_password_enter_puk_code);
        mKeyguardStatusViewManager.onResume();
    }

    /** {@inheritDoc} */
    public void cleanUp() {
        // dismiss the dialog.
        if (mSimUnlockProgressDialog != null) {
            mSimUnlockProgressDialog.dismiss();
            mSimUnlockProgressDialog = null;
        }
        mUpdateMonitor.removeCallback(this);
    }


    /**
     * Since the IPC can block, we want to run the request in a separate thread
     * with a callback.
     */
    private abstract class CheckSimPuk extends Thread {

        private final String mPin, mPuk;

        protected CheckSimPuk(String puk, String pin) {
            mPuk = puk;
            mPin = pin;
        }

        abstract void onSimLockChangedResponse(boolean success);

        @Override
        public void run() {
            try {
                final boolean result = ITelephony.Stub.asInterface(ServiceManager
                        .checkService("phone")).supplyPuk(mPuk, mPin);

                post(new Runnable() {
                    public void run() {
                        onSimLockChangedResponse(result);
                    }
                });
            } catch (RemoteException e) {
                post(new Runnable() {
                    public void run() {
                        onSimLockChangedResponse(false);
                    }
                });
            }
        }
    }

    public void onClick(View v) {
        if (v == mDelPukButton) {
            if (mFocusedEntry != mPukText)
                mPukText.requestFocus();
            final Editable digits = mPukText.getEditableText();
            final int len = digits.length();
            if (len > 0) {
                digits.delete(len-1, len);
            }
        } else if (v == mDelPinButton) {
            if (mFocusedEntry != mPinText)
                mPinText.requestFocus();
            final Editable digits = mPinText.getEditableText();
            final int len = digits.length();
            if (len > 0) {
                digits.delete(len-1, len);
            }
        } else if (v == mOkButton) {
            checkPuk();
        }
        mCallback.pokeWakelock(DIGIT_PRESS_WAKE_MILLIS);

    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (hasFocus)
            mFocusedEntry = (TextView)v;
    }

    private Dialog getSimUnlockProgressDialog() {
        if (mSimUnlockProgressDialog == null) {
            mSimUnlockProgressDialog = new ProgressDialog(mContext);
            mSimUnlockProgressDialog.setMessage(
                    mContext.getString(R.string.lockscreen_sim_unlock_progress_dialog_message));
            mSimUnlockProgressDialog.setIndeterminate(true);
            mSimUnlockProgressDialog.setCancelable(false);
            mSimUnlockProgressDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        }
        return mSimUnlockProgressDialog;
    }

    private void checkPuk() {
        // make sure that the puk is at least 8 digits long.
        if (mPukText.getText().length() < 8) {
            // otherwise, display a message to the user, and don't submit.
            mHeaderText.setText(R.string.invalidPuk);
            mPukText.setText("");
            return;
        }

        if (mPinText.getText().length() < 4
                || mPinText.getText().length() > 8) {
            // otherwise, display a message to the user, and don't submit.
            mHeaderText.setText(R.string.invalidPin);
            mPinText.setText("");
            return;
        }

        getSimUnlockProgressDialog().show();

        new CheckSimPuk(mPukText.getText().toString(),
                mPinText.getText().toString()) {
            void onSimLockChangedResponse(final boolean success) {
                mPinText.post(new Runnable() {
                    public void run() {
                        if (mSimUnlockProgressDialog != null) {
                            mSimUnlockProgressDialog.hide();
                        }
                        if (success) {
                            // before closing the keyguard, report back that
                            // the sim is unlocked so it knows right away
                            mUpdateMonitor.reportSimUnlocked();
                            mCallback.goToUnlockScreen();
                        } else {
                            mHeaderText.setText(R.string.badPuk);
                            mPukText.setText("");
                            mPinText.setText("");
                        }
                    }
                });
            }
        }.start();
    }


    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mCallback.goToLockScreen();
            return true;
        }
        final char match = event.getMatch(DIGITS);
        if (match != 0) {
            reportDigit(match - '0');
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            mFocusedEntry.onKeyDown(keyCode, event);
            final Editable digits = mFocusedEntry.getEditableText();
            final int len = digits.length();
            if (len > 0) {
                digits.delete(len-1, len);
            }
            mCallback.pokeWakelock(DIGIT_PRESS_WAKE_MILLIS);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            checkPuk();
            return true;
        }

        return false;
    }

    private void reportDigit(int digit) {
        mFocusedEntry.append(Integer.toString(digit));
    }

    void updateConfiguration() {
        Configuration newConfig = getResources().getConfiguration();
        if (newConfig.orientation != mCreationOrientation) {
            mCallback.recreateMe(newConfig);
        } else if (newConfig.hardKeyboardHidden != mKeyboardHidden) {
            mKeyboardHidden = newConfig.hardKeyboardHidden;
        }

    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateConfiguration();
    }

    /** {@inheritDoc} */
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateConfiguration();
    }

    /**
     * Helper class to handle input from touch dialer.  Only relevant when
     * the keyboard is shut.
     */
    private class TouchInput implements View.OnClickListener {
        private TextView mZero;
        private TextView mOne;
        private TextView mTwo;
        private TextView mThree;
        private TextView mFour;
        private TextView mFive;
        private TextView mSix;
        private TextView mSeven;
        private TextView mEight;
        private TextView mNine;
        private TextView mCancelButton;

        private TouchInput() {
            mZero = (TextView) findViewById(R.id.zero);
            mOne = (TextView) findViewById(R.id.one);
            mTwo = (TextView) findViewById(R.id.two);
            mThree = (TextView) findViewById(R.id.three);
            mFour = (TextView) findViewById(R.id.four);
            mFive = (TextView) findViewById(R.id.five);
            mSix = (TextView) findViewById(R.id.six);
            mSeven = (TextView) findViewById(R.id.seven);
            mEight = (TextView) findViewById(R.id.eight);
            mNine = (TextView) findViewById(R.id.nine);
            mCancelButton = (TextView) findViewById(R.id.cancel);

            mZero.setText("0");
            mOne.setText("1");
            mTwo.setText("2");
            mThree.setText("3");
            mFour.setText("4");
            mFive.setText("5");
            mSix.setText("6");
            mSeven.setText("7");
            mEight.setText("8");
            mNine.setText("9");

            mZero.setOnClickListener(this);
            mOne.setOnClickListener(this);
            mTwo.setOnClickListener(this);
            mThree.setOnClickListener(this);
            mFour.setOnClickListener(this);
            mFive.setOnClickListener(this);
            mSix.setOnClickListener(this);
            mSeven.setOnClickListener(this);
            mEight.setOnClickListener(this);
            mNine.setOnClickListener(this);
            mCancelButton.setOnClickListener(this);
        }


        public void onClick(View v) {
            if (v == mCancelButton) {
                // clear the PIN/PUK entry fields if the user cancels
                mPinText.setText("");
                mPukText.setText("");
                mCallback.goToLockScreen();
                return;
            }

            final int digit = checkDigit(v);
            if (digit >= 0) {
                mCallback.pokeWakelock(DIGIT_PRESS_WAKE_MILLIS);
                reportDigit(digit);
            }
        }

        private int checkDigit(View v) {
            int digit = -1;
            if (v == mZero) {
                digit = 0;
            } else if (v == mOne) {
                digit = 1;
            } else if (v == mTwo) {
                digit = 2;
            } else if (v == mThree) {
                digit = 3;
            } else if (v == mFour) {
                digit = 4;
            } else if (v == mFive) {
                digit = 5;
            } else if (v == mSix) {
                digit = 6;
            } else if (v == mSeven) {
                digit = 7;
            } else if (v == mEight) {
                digit = 8;
            } else if (v == mNine) {
                digit = 9;
            }
            return digit;
        }
    }

}
