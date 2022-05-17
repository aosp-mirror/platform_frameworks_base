/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.usb;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.systemui.R;

abstract class UsbDialogActivity extends AlertActivity
        implements DialogInterface.OnClickListener, CheckBox.OnCheckedChangeListener {

    private static final String TAG = UsbDialogActivity.class.getSimpleName();

    UsbDialogHelper mDialogHelper;
    private CheckBox mAlwaysUse;
    private TextView mClearDefaultHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addSystemFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        try {
            mDialogHelper = new UsbDialogHelper(getApplicationContext(), getIntent());
        } catch (IllegalStateException e) {
            Log.e(TAG, "unable to initialize", e);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mDialogHelper.registerUsbDisconnectedReceiver(this);
    }

    @Override
    protected void onPause() {
        if (mDialogHelper != null) {
            mDialogHelper.unregisterUsbDisconnectedReceiver(this);
        }
        super.onPause();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == AlertDialog.BUTTON_POSITIVE) {
            onConfirm();
        } else {
            finish();
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (mClearDefaultHint == null) return;

        if (isChecked) {
            mClearDefaultHint.setVisibility(View.VISIBLE);
        } else {
            mClearDefaultHint.setVisibility(View.GONE);
        }
    }

    void setAlertParams(String title, String message) {
        final AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = title;
        ap.mMessage = message;
        ap.mPositiveButtonText = getString(android.R.string.ok);
        ap.mNegativeButtonText = getString(android.R.string.cancel);
        ap.mPositiveButtonListener = this;
        ap.mNegativeButtonListener = this;
    }

    void addAlwaysUseCheckbox() {
        final AlertController.AlertParams ap = mAlertParams;
        LayoutInflater inflater = getSystemService(LayoutInflater.class);
        ap.mView = inflater.inflate(com.android.internal.R.layout.always_use_checkbox, null);
        mAlwaysUse = ap.mView.findViewById(com.android.internal.R.id.alwaysUse);
        if (mDialogHelper.isUsbAccessory()) {
            mAlwaysUse.setText(getString(R.string.always_use_accessory, mDialogHelper.getAppName(),
                    mDialogHelper.getDeviceDescription()));
        } else {
            // UsbDevice case
            mAlwaysUse.setText(getString(R.string.always_use_device, mDialogHelper.getAppName(),
                    mDialogHelper.getDeviceDescription()));
        }
        mAlwaysUse.setOnCheckedChangeListener(this);
        mClearDefaultHint = ap.mView.findViewById(com.android.internal.R.id.clearDefaultHint);
        mClearDefaultHint.setVisibility(View.GONE);
    }

    boolean isAlwaysUseChecked() {
        return mAlwaysUse != null && mAlwaysUse.isChecked();
    }

    /**
     * Called when the dialog is confirmed.
     */
    abstract void onConfirm();
}
