/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.usb.ParcelableUsbPort;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.systemui.R;

/**
 * Activity that alerts the user when contaminant is detected on USB port.
 */
public class UsbContaminantActivity extends AlertActivity
                                  implements DialogInterface.OnClickListener {
    private static final String TAG = "UsbContaminantActivity";

    private UsbDisconnectedReceiver mDisconnectedReceiver;
    private UsbPort mUsbPort;

    @Override
    public void onCreate(Bundle icicle) {
        Window window = getWindow();
        window.addSystemFlags(WindowManager.LayoutParams
                .SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);

        super.onCreate(icicle);

        Intent intent = getIntent();
        ParcelableUsbPort port = intent.getParcelableExtra(UsbManager.EXTRA_PORT);
        mUsbPort = port.getUsbPort(getSystemService(UsbManager.class));

        final AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = getString(R.string.usb_contaminant_title);
        ap.mMessage = getString(R.string.usb_contaminant_message);
        ap.mPositiveButtonText = getString(android.R.string.ok);
        ap.mPositiveButtonListener = this;

        setupAlert();
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
        super.onWindowAttributesChanged(params);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        finish();
    }
}
