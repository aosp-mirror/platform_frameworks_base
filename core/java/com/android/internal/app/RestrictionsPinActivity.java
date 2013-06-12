/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.UserManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import com.android.internal.R;

/**
 * This activity is launched by Settings and other apps to either create a new PIN or
 * challenge for an existing PIN. The PIN is maintained by UserManager.
 */
public class RestrictionsPinActivity extends AlertActivity
        implements DialogInterface.OnClickListener, TextWatcher, OnEditorActionListener {

    private UserManager mUserManager;

    private EditText mPin1Text;
    private EditText mPin2Text;
    private TextView mPinErrorMessage;
    private TextView mPinMessage;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = getString(R.string.restr_pin_enter_pin);
        ap.mPositiveButtonText = getString(R.string.ok);
        ap.mNegativeButtonText = getString(R.string.cancel);
        ap.mPositiveButtonListener = this;
        ap.mNegativeButtonListener = this;
        LayoutInflater inflater =
                (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        ap.mView = inflater.inflate(R.layout.pin_challenge, null);

        mPinMessage = (TextView) ap.mView.findViewById(R.id.pin_message);
        mPin1Text = (EditText) ap.mView.findViewById(R.id.pin1_text);
        mPin2Text = (EditText) ap.mView.findViewById(R.id.pin2_text);
        mPinErrorMessage = (TextView) ap.mView.findViewById(R.id.pin_error_message);
        mPin1Text.addTextChangedListener(this);
        mPin2Text.addTextChangedListener(this);

        mUserManager = (UserManager) getSystemService(Context.USER_SERVICE);

        setupAlert();
    }

    protected boolean verifyingPin() {
        return true;
    }

    public void onResume() {
        super.onResume();

        setPositiveButtonState(false);
        boolean hasPin = mUserManager.hasRestrictionsPin();
        if (verifyingPin()) {
            if (hasPin) {
                mPinMessage.setVisibility(View.GONE);
                mPinErrorMessage.setVisibility(View.GONE);
                mPin2Text.setVisibility(View.GONE);
                mPin1Text.setOnEditorActionListener(this);
                updatePinTimer(-1);
            } else {
                setResult(RESULT_OK);
                finish();
            }
        } else if (hasPin) {
            // Shouldn't really be in this state, exit
            setResult(RESULT_OK);
            finish();
        }
    }

    private void setPositiveButtonState(boolean enabled) {
        mAlert.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(enabled);
    }

    private void updatePinTimer(int pinTimerMs) {
        if (pinTimerMs < 0) {
            pinTimerMs = mUserManager.checkRestrictionsPin(null);
        }
        if (pinTimerMs >= 200) {
            final int seconds = (pinTimerMs + 200) / 1000;
            final String formatString = getResources().getQuantityString(
                    R.plurals.restr_pin_countdown,
                    seconds);
            mPinErrorMessage.setText(String.format(formatString, seconds));
            mPinErrorMessage.setVisibility(View.VISIBLE);
            mPin1Text.setEnabled(false);
            mPin1Text.setText("");
            setPositiveButtonState(false);
            mPin1Text.postDelayed(mCountdownRunnable, Math.min(1000, pinTimerMs));
        } else {
            mPinErrorMessage.setVisibility(View.INVISIBLE);
            mPin1Text.setEnabled(true);
            mPin1Text.setText("");
        }
    }

    public void onClick(DialogInterface dialog, int which) {
        setResult(RESULT_CANCELED);
        if (which == AlertDialog.BUTTON_POSITIVE) {
            performPositiveButtonAction();
        } else if (which == AlertDialog.BUTTON_NEGATIVE) {
            finish();
        }
    }

    private void performPositiveButtonAction() {
        if (verifyingPin()) {
            int result = mUserManager.checkRestrictionsPin(mPin1Text.getText().toString());
            if (result == UserManager.PIN_VERIFICATION_SUCCESS) {
                setResult(RESULT_OK);
                finish();
            } else if (result >= 0) {
                updatePinTimer(result);
            }
        } else {
            if (mUserManager.changeRestrictionsPin(mPin1Text.getText().toString())) {
                setResult(RESULT_OK);
                finish();
            }
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        CharSequence pin1 = mPin1Text.getText();
        if (!verifyingPin()) {
            CharSequence pin2 = mPin2Text.getText();
            boolean match = pin1 != null && pin2 != null && pin1.length() >= 4
                    && pin1.toString().equals(pin2.toString());
            setPositiveButtonState(match);
            mPinErrorMessage.setVisibility(match ? View.INVISIBLE : View.VISIBLE);
        } else {
            setPositiveButtonState(pin1 != null && pin1.length() >= 4);
        }
    }

    @Override
    public void afterTextChanged(Editable s) {
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        performPositiveButtonAction();
        return true;
    }

    private Runnable mCountdownRunnable = new Runnable() {
        public void run() {
            updatePinTimer(-1);
        }
    };
}
