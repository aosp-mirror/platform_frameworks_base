/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.util.Log;
import android.widget.Button;

import java.io.File;

import com.android.systemui.R;

public class UsbPreferenceActivity extends Activity implements View.OnClickListener  {

    private static final String TAG = "UsbPreferenceActivity";

    private UsbManager mUsbManager;
    private String mCurrentFunction;
    private String[] mFunctions;
    private String mInstallerImagePath;
    private Button mMtpPtpButton;
    private Button mInstallerCdButton;
    private boolean mPtpActive;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mUsbManager = (UsbManager)getSystemService(Context.USB_SERVICE);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);
        dialogBuilder.setTitle(getString(R.string.usb_preference_title));

        LayoutInflater inflater = (LayoutInflater)getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        View buttonView = inflater.inflate(R.layout.usb_preference_buttons, null);
        dialogBuilder.setView(buttonView);
        mMtpPtpButton = (Button)buttonView.findViewById(R.id.mtp_ptp_button);
        mInstallerCdButton = (Button)buttonView.findViewById(R.id.installer_cd_button);
        mMtpPtpButton.setOnClickListener(this);
        mInstallerCdButton.setOnClickListener(this);

        mPtpActive = mUsbManager.isFunctionEnabled(UsbManager.USB_FUNCTION_PTP);
        if (mPtpActive) {
            mMtpPtpButton.setText(R.string.use_mtp_button_title);
        }

        mInstallerImagePath = getString(com.android.internal.R.string.config_isoImagePath);
        if (!(new File(mInstallerImagePath)).exists()) {
            mInstallerCdButton.setVisibility(View.GONE);
        }

        dialogBuilder.show();
    }

    public void onClick(View v) {
        if (v.equals(mMtpPtpButton)) {
            if (mPtpActive) {
                mUsbManager.setPrimaryFunction(UsbManager.USB_FUNCTION_MTP);
            } else {
                mUsbManager.setPrimaryFunction(UsbManager.USB_FUNCTION_PTP);
            }
        } else if (v.equals(mInstallerCdButton)) {
            mUsbManager.setPrimaryFunction(UsbManager.USB_FUNCTION_MASS_STORAGE);
            mUsbManager.setMassStorageBackingFile(mInstallerImagePath);
        }

        finish();
    }
}
