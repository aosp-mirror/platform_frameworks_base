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

package com.android.systemui.usb;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.debug.IAdbManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.systemui.R;

public class UsbDebuggingActivity extends AlertActivity
                                  implements DialogInterface.OnClickListener {
    private static final String TAG = "UsbDebuggingActivity";

    private CheckBox mAlwaysAllow;
    private String mKey;

    @Override
    public void onCreate(Bundle icicle) {
        Window window = getWindow();
        window.addSystemFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);

        super.onCreate(icicle);

        Intent intent = getIntent();
        String fingerprints = intent.getStringExtra("fingerprints");
        mKey = intent.getStringExtra("key");

        if (fingerprints == null || mKey == null) {
            finish();
            return;
        }

        final AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = getString(R.string.usb_debugging_title);
        ap.mMessage = getString(R.string.usb_debugging_message, fingerprints);
        ap.mPositiveButtonText = getString(R.string.usb_debugging_allow);
        ap.mNegativeButtonText = getString(android.R.string.cancel);
        ap.mPositiveButtonListener = this;
        ap.mNegativeButtonListener = this;

        // add "always allow" checkbox
        LayoutInflater inflater = LayoutInflater.from(ap.mContext);
        View checkbox = inflater.inflate(com.android.internal.R.layout.always_use_checkbox, null);
        mAlwaysAllow = (CheckBox)checkbox.findViewById(com.android.internal.R.id.alwaysUse);
        mAlwaysAllow.setText(getString(R.string.usb_debugging_always));
        ap.mView = checkbox;
        window.setCloseOnTouchOutside(false);

        setupAlert();
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
        super.onWindowAttributesChanged(params);
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        boolean allow = (which == AlertDialog.BUTTON_POSITIVE);
        boolean alwaysAllow = allow && mAlwaysAllow.isChecked();
        try {
            IBinder b = ServiceManager.getService(ADB_SERVICE);
            IAdbManager service = IAdbManager.Stub.asInterface(b);
            if (allow) {
                service.allowDebugging(alwaysAllow, mKey);
            } else {
                service.denyDebugging();
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to notify Usb service", e);
        }
        finish();
    }
}
