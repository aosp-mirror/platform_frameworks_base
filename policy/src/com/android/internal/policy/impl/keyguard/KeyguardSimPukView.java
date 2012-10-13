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

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import com.android.internal.telephony.ITelephony;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.PasswordEntryKeyboardHelper;
import com.android.internal.widget.PasswordEntryKeyboardView;
import com.android.internal.R;

public class KeyguardSimPukView extends LinearLayout implements View.OnClickListener,
    KeyguardSecurityView, OnEditorActionListener, TextWatcher {

    private View mDeleteButton;

    private ProgressDialog mSimUnlockProgressDialog = null;
    private KeyguardSecurityCallback mCallback;

    private SecurityMessageDisplay mSecurityMessageDisplay;

    private PasswordEntryKeyboardView mKeyboardView;

    private PasswordEntryKeyboardHelper mKeyboardHelper;

    private LockPatternUtils mLockPatternUtils;

    private volatile boolean mCheckInProgress;

    private TextView mSimPinEntry;

    private String mPukText;

    private String mPinText;
    private StateMachine mStateMachine = new StateMachine();

    private class StateMachine {
        final int ENTER_PUK = 0;
        final int ENTER_PIN = 1;
        final int CONFIRM_PIN = 2;
        final int DONE = 3;
        private int state = ENTER_PUK;

        public void next() {
            int msg = 0;
            if (state == ENTER_PUK) {
                if (checkPuk()) {
                    state = ENTER_PIN;
                    msg = R.string.kg_puk_enter_pin_hint;
                } else {
                    msg = R.string.kg_invalid_sim_puk_hint;
                }
            } else if (state == ENTER_PIN) {
                if (checkPin()) {
                    state = CONFIRM_PIN;
                    msg = R.string.kg_enter_confirm_pin_hint;
                } else {
                    msg = R.string.kg_invalid_sim_pin_hint;
                }
            } else if (state == CONFIRM_PIN) {
                if (confirmPin()) {
                    state = DONE;
                    msg = R.string.kg_login_checking_password;
                    updateSim();
                } else {
                    msg = R.string.kg_invalid_confirm_pin_hint;
                }
            }
            mSimPinEntry.setText(null);
            if (msg != 0) {
                mSecurityMessageDisplay.setMessage(msg, true);
            }
        }

        void reset() {
            mPinText="";
            mPukText="";
            state = ENTER_PUK;
            if (mSecurityMessageDisplay != null) {
                mSecurityMessageDisplay.setMessage(R.string.kg_puk_enter_puk_hint, true);
            }
            mSimPinEntry.requestFocus();
        }
    }

    public KeyguardSimPukView(Context context) {
        this(context, null);
    }

    public KeyguardSimPukView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(getContext());
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
        mLockPatternUtils = new LockPatternUtils(getContext());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mSimPinEntry = (TextView) findViewById(R.id.sim_pin_entry);
        mSimPinEntry.setOnEditorActionListener(this);
        mSimPinEntry.addTextChangedListener(this);
        mDeleteButton = findViewById(R.id.delete_button);
        mDeleteButton.setOnClickListener(this);
        mKeyboardView = (PasswordEntryKeyboardView) findViewById(R.id.keyboard);
        mKeyboardHelper = new PasswordEntryKeyboardHelper(mContext, mKeyboardView, this, false,
                new int[] {
                R.xml.kg_password_kbd_numeric,
                com.android.internal.R.xml.password_kbd_qwerty,
                com.android.internal.R.xml.password_kbd_qwerty_shifted,
                com.android.internal.R.xml.password_kbd_symbols,
                com.android.internal.R.xml.password_kbd_symbols_shift
                });
        mKeyboardHelper.setKeyboardMode(PasswordEntryKeyboardHelper.KEYBOARD_MODE_NUMERIC);
        mKeyboardHelper.setEnableHaptics(mLockPatternUtils.isTactileFeedbackEnabled());
        reset();
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        return mSimPinEntry.requestFocus(direction, previouslyFocusedRect);
    }

    public boolean needsInput() {
        return false; // This view provides its own keypad
    }

    public void onPause() {

    }

    public void onResume() {
        reset();
    }

    /** {@inheritDoc} */
    public void cleanUp() {
        // dismiss the dialog.
        if (mSimUnlockProgressDialog != null) {
            mSimUnlockProgressDialog.dismiss();
            mSimUnlockProgressDialog = null;
        }
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
        if (v == mDeleteButton) {
            mSimPinEntry.requestFocus();
            final Editable digits = mSimPinEntry.getEditableText();
            final int len = digits.length();
            if (len > 0) {
                digits.delete(len-1, len);
            }
        }
        mCallback.userActivity(KeyguardViewManager.DIGIT_PRESS_WAKE_MILLIS);
    }

    private Dialog getSimUnlockProgressDialog() {
        if (mSimUnlockProgressDialog == null) {
            mSimUnlockProgressDialog = new ProgressDialog(mContext);
            mSimUnlockProgressDialog.setMessage(mContext.getString(
                    R.string.kg_sim_unlock_progress_dialog_message));
            mSimUnlockProgressDialog.setIndeterminate(true);
            mSimUnlockProgressDialog.setCancelable(false);
            if (!(mContext instanceof Activity)) {
                mSimUnlockProgressDialog.getWindow().setType(
                        WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            }
        }
        return mSimUnlockProgressDialog;
    }

    private boolean checkPuk() {
        // make sure the puk is at least 8 digits long.
        if (mSimPinEntry.getText().length() >= 8) {
            mPukText = mSimPinEntry.getText().toString();
            return true;
        }
        return false;
    }

    private boolean checkPin() {
        // make sure the PIN is between 4 and 8 digits
        int length = mSimPinEntry.getText().length();
        if (length >= 4 && length <= 8) {
            mPinText = mSimPinEntry.getText().toString();
            return true;
        }
        return false;
    }

    public boolean confirmPin() {
        return mPinText.equals(mSimPinEntry.getText().toString());
    }

    private void updateSim() {
        getSimUnlockProgressDialog().show();

        if (!mCheckInProgress) {
            mCheckInProgress = true;
            new CheckSimPuk(mPukText, mPinText) {
                void onSimLockChangedResponse(final boolean success) {
                    post(new Runnable() {
                        public void run() {
                            if (mSimUnlockProgressDialog != null) {
                                mSimUnlockProgressDialog.hide();
                            }
                            if (success) {
                                mCallback.dismiss(true);
                            } else {
                                mStateMachine.reset();
                                mSecurityMessageDisplay.setMessage(R.string.kg_invalid_puk, true);
                            }
                            mCheckInProgress = false;
                        }
                    });
                }
            }.start();
        }
    }

    @Override
    public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
        // Check if this was the result of hitting the enter key
        mCallback.userActivity(KeyguardViewManager.DIGIT_PRESS_WAKE_MILLIS);
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (actionId == EditorInfo.IME_NULL || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_NEXT) {
                mStateMachine.next();
                return true;
            }
        }
        return false;
    }

    @Override
    public void setLockPatternUtils(LockPatternUtils utils) {
        mLockPatternUtils = utils;
    }

    @Override
    public void reset() {
        mStateMachine.reset();
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

    @Override
    public void setSecurityMessageDisplay(SecurityMessageDisplay display) {
        mSecurityMessageDisplay = display;
        reset();
    }
}
