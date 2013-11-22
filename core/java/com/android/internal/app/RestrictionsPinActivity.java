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

import android.content.Context;
import android.os.Bundle;
import android.os.UserManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import com.android.internal.R;

/**
 * This activity is launched by Settings and other apps to either create a new PIN or
 * challenge for an existing PIN. The PIN is maintained by UserManager.
 */
public class RestrictionsPinActivity extends AlertActivity
        implements OnClickListener, TextWatcher, OnEditorActionListener {

    protected UserManager mUserManager;
    protected boolean mHasRestrictionsPin;

    protected EditText mPinText;
    protected TextView mPinErrorMessage;
    private Button mOkButton;
    private Button mCancelButton;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mUserManager = (UserManager) getSystemService(Context.USER_SERVICE);
        mHasRestrictionsPin = mUserManager.hasRestrictionsChallenge();
        initUi();
        setupAlert();
    }

    protected void initUi() {
        AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = getString(R.string.restr_pin_enter_admin_pin);
        LayoutInflater inflater =
                (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        ap.mView = inflater.inflate(R.layout.restrictions_pin_challenge, null);

        mPinErrorMessage = (TextView) ap.mView.findViewById(R.id.pin_error_message);
        mPinText = (EditText) ap.mView.findViewById(R.id.pin_text);
        mOkButton = (Button) ap.mView.findViewById(R.id.pin_ok_button);
        mCancelButton = (Button) ap.mView.findViewById(R.id.pin_cancel_button);

        mPinText.addTextChangedListener(this);

        mOkButton.setOnClickListener(this);
        mCancelButton.setOnClickListener(this);
    }

    protected boolean verifyingPin() {
        return true;
    }

    public void onResume() {
        super.onResume();

        setPositiveButtonState(false);
        boolean hasPin = mUserManager.hasRestrictionsChallenge();
        if (hasPin) {
            mPinErrorMessage.setVisibility(View.INVISIBLE);
            mPinText.setOnEditorActionListener(this);
            updatePinTimer(-1);
        } else if (verifyingPin()) {
            setResult(RESULT_OK);
            finish();
        }
    }

    protected void setPositiveButtonState(boolean enabled) {
        mOkButton.setEnabled(enabled);
    }

    private boolean updatePinTimer(int pinTimerMs) {
        if (pinTimerMs < 0) {
            pinTimerMs = mUserManager.checkRestrictionsChallenge(null);
        }
        boolean enableInput;
        if (pinTimerMs >= 200) {
            // Do the count down timer for less than a minute, otherwise just say try again later.
            if (pinTimerMs <= 60000) {
                final int seconds = (pinTimerMs + 200) / 1000;
                final String formatString = getResources().getQuantityString(
                        R.plurals.restr_pin_countdown,
                        seconds);
                mPinErrorMessage.setText(String.format(formatString, seconds));
            } else {
                mPinErrorMessage.setText(R.string.restr_pin_try_later);
            }
            enableInput = false;
            mPinErrorMessage.setVisibility(View.VISIBLE);
            mPinText.setText("");
            mPinText.postDelayed(mCountdownRunnable, Math.min(1000, pinTimerMs));
        } else {
            enableInput = true;
            mPinErrorMessage.setText(R.string.restr_pin_incorrect);
        }
        mPinText.setEnabled(enableInput);
        setPositiveButtonState(enableInput);
        return enableInput;
    }

    protected void performPositiveButtonAction() {
        int result = mUserManager.checkRestrictionsChallenge(mPinText.getText().toString());
        if (result == UserManager.PIN_VERIFICATION_SUCCESS) {
            setResult(RESULT_OK);
            finish();
        } else if (result >= 0) {
            mPinErrorMessage.setText(R.string.restr_pin_incorrect);
            mPinErrorMessage.setVisibility(View.VISIBLE);
            updatePinTimer(result);
            mPinText.setText("");
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        CharSequence pin = mPinText.getText();
        setPositiveButtonState(pin != null && pin.length() >= 4);
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
            if (updatePinTimer(-1)) {
                // If we are no longer counting down, clear the message.
                mPinErrorMessage.setVisibility(View.INVISIBLE);
            }
        }
    };

    @Override
    public void onClick(View v) {
        if (v == mOkButton) {
            performPositiveButtonAction();
        } else if (v == mCancelButton) {
            setResult(RESULT_CANCELED);
            finish();
        }
    }
}
