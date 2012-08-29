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
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.Editable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
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
    View.OnFocusChangeListener, KeyguardSecurityView, OnEditorActionListener {

    private static final int DIGIT_PRESS_WAKE_MILLIS = 5000;

    private TextView mPukText;
    private TextView mPinText;
    private TextView mFocusedEntry;

    private View mDelPukButton;
    private View mDelPinButton;

    private ProgressDialog mSimUnlockProgressDialog = null;
    private KeyguardSecurityCallback mCallback;

    private KeyguardNavigationManager mNavigationManager;

    private PasswordEntryKeyboardView mKeyboardView;

    private PasswordEntryKeyboardHelper mKeyboardHelper;

    private LockPatternUtils mLockPatternUtils;

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

        mNavigationManager = new KeyguardNavigationManager(this);

        mPukText = (TextView) findViewById(R.id.sim_puk_entry);
        mPukText.setOnEditorActionListener(this);
        mPinText = (TextView) findViewById(R.id.sim_pin_entry);
        mPinText.setOnEditorActionListener(this);
        mDelPukButton = findViewById(R.id.puk_delete_button);
        mDelPukButton.setOnClickListener(this);
        mDelPinButton = findViewById(R.id.pin_delete_button);
        mDelPinButton.setOnClickListener(this);

        mKeyboardView = (PasswordEntryKeyboardView) findViewById(R.id.keyboard);
        mKeyboardHelper = new PasswordEntryKeyboardHelper(mContext, mKeyboardView, this, false);
        mKeyboardHelper.setKeyboardMode(PasswordEntryKeyboardHelper.KEYBOARD_MODE_NUMERIC);
        mKeyboardHelper.setEnableHaptics(mLockPatternUtils.isTactileFeedbackEnabled());

        mNavigationManager.setMessage(R.string.kg_sim_puk_recovery_hint);

        mPinText.setFocusableInTouchMode(true);
        mPinText.setOnFocusChangeListener(this);
        mPukText.setFocusableInTouchMode(true);
        mPukText.setOnFocusChangeListener(this);

        setFocusableInTouchMode(true);

        reset();
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        return mPukText.requestFocus(direction, previouslyFocusedRect);
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
        }
        mCallback.userActivity(DIGIT_PRESS_WAKE_MILLIS);
    }

    @Override
    public void onFocusChange(View view, boolean hasFocus) {
        if (hasFocus)
            mFocusedEntry = (TextView) view;
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

    private void checkPuk() {
        // make sure the puk is at least 8 digits long.
        if (mPukText.getText().length() < 8) {
            // otherwise, display a message to the user, and don't submit.
            mNavigationManager.setMessage(R.string.kg_invalid_sim_puk_hint);
            mPukText.setText("");
            return;
        }

        // make sure the PIN is between 4 and 8 digits
        if (mPinText.getText().length() < 4
                || mPinText.getText().length() > 8) {
            // otherwise, display a message to the user, and don't submit.
            mNavigationManager.setMessage(R.string.kg_invalid_sim_pin_hint);
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
                            mCallback.dismiss(true);
                        } else {
                            mNavigationManager.setMessage(R.string.kg_invalid_puk);
                            mPukText.setText("");
                            mPinText.setText("");
                        }
                    }
                });
            }
        }.start();
    }

    @Override
    public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
        // Check if this was the result of hitting the enter key
        mCallback.userActivity(DIGIT_PRESS_WAKE_MILLIS);
        if (actionId == EditorInfo.IME_NULL
                || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_NEXT) {
            if (view == mPukText && mPukText.getText().length() < 8) {
                mNavigationManager.setMessage(R.string.kg_invalid_sim_puk_hint);
                mPukText.setText("");
                mPukText.requestFocus();
                return true;
            } else if (view == mPinText) {
                if (mPinText.getText().length() < 4 || mPinText.getText().length() > 8) {
                    mNavigationManager.setMessage(R.string.kg_invalid_sim_pin_hint);
                    mPinText.setText("");
                    mPinText.requestFocus();
                } else {
                    checkPuk();
                }
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
        mNavigationManager.setMessage(R.string.kg_sim_puk_recovery_hint);
        mPinText.setText("");
        mPukText.setText("");
        mPukText.requestFocus();
    }

    @Override
    public KeyguardSecurityCallback getCallback() {
        return mCallback;
    }

}
