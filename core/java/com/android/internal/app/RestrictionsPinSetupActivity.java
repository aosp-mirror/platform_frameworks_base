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
import android.os.UserManager;
import android.text.Editable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.android.internal.R;

/**
 * This activity is launched by Settings and other apps to either create a new PIN or
 * change an existing PIN. The PIN is maintained by UserManager.
 */
public class RestrictionsPinSetupActivity extends RestrictionsPinActivity {

    private EditText mNewPinText;
    private EditText mConfirmPinText;

    protected void initUi() {
        AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = getString(R.string.restr_pin_enter_pin);
        ap.mPositiveButtonText = getString(R.string.ok);
        ap.mNegativeButtonText = getString(R.string.cancel);
        ap.mPositiveButtonListener = this;
        ap.mNegativeButtonListener = this;
        LayoutInflater inflater =
                (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        ap.mView = inflater.inflate(R.layout.restrictions_pin_setup, null);

        mPinText = (EditText) ap.mView.findViewById(R.id.pin_text);
        mPinMessage = (TextView) ap.mView.findViewById(R.id.pin_message);
        mNewPinText = (EditText) ap.mView.findViewById(R.id.pin_new_text);
        mConfirmPinText = (EditText) ap.mView.findViewById(R.id.pin_confirm_text);
        mPinErrorMessage = (TextView) ap.mView.findViewById(R.id.pin_error_message);
        mNewPinText.addTextChangedListener(this);
        mConfirmPinText.addTextChangedListener(this);

        if (!mHasRestrictionsPin) {
            mPinText.setVisibility(View.GONE);
        }
    }

    public void onResume() {
        super.onResume();
        setPositiveButtonState(false);
    }

    protected boolean verifyingPin() {
        return false;
    }

    private void setPositiveButtonState(boolean enabled) {
        mAlert.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(enabled);
    }

    public void onClick(DialogInterface dialog, int which) {
        setResult(RESULT_CANCELED);
        if (which == AlertDialog.BUTTON_POSITIVE) {
            performPositiveButtonAction();
        } else if (which == AlertDialog.BUTTON_NEGATIVE) {
            finish();
        }
    }

    protected void performPositiveButtonAction() {
        if (mHasRestrictionsPin) {
            int result = mUserManager.checkRestrictionsPin(mPinText.getText().toString());
            if (result != UserManager.PIN_VERIFICATION_SUCCESS) {
                // TODO: Set message that existing pin doesn't match
                return;
            }
        }
        if (mUserManager.changeRestrictionsPin(mNewPinText.getText().toString())) {
            // TODO: Send message to PIN recovery agent about the recovery email address
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        CharSequence pin = mPinText.getText();
        CharSequence pin1 = mNewPinText.getText();
        CharSequence pin2 = mConfirmPinText.getText();
        boolean match = pin1 != null && pin2 != null && pin1.length() >= 4
                && pin1.toString().equals(pin2.toString())
                && (!mHasRestrictionsPin || (pin != null && pin.length() >= 4));
        boolean showError = !TextUtils.isEmpty(pin1) && !TextUtils.isEmpty(pin2);
        // TODO: Check recovery email address as well
        setPositiveButtonState(match);
        mPinErrorMessage.setVisibility((match || !showError) ? View.INVISIBLE : View.VISIBLE);
    }

    @Override
    public void afterTextChanged(Editable s) {
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        performPositiveButtonAction();
        return true;
    }
}
