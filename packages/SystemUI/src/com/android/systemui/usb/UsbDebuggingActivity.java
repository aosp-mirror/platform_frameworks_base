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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;

import com.android.systemui.R;

public class UsbDebuggingActivity extends AlertActivity
                                  implements DialogInterface.OnClickListener {
    private static final String TAG = "UsbDebuggingActivity";

    private CheckBox mAlwaysAllow;
    private UsbDisconnectedReceiver mDisconnectedReceiver;
    private String mKey;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mDisconnectedReceiver = new UsbDisconnectedReceiver(this);
        Intent intent = getIntent();
        String fingerprints = intent.getStringExtra("fingerprints");
        mKey = intent.getStringExtra("key");

        if (fingerprints == null || mKey == null) {
            finish();
            return;
        }

        final AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = getString(R.string.usb_debugging_title);
        ap.mIconId = com.android.internal.R.drawable.ic_dialog_usb;
        ap.mMessage = getString(R.string.usb_debugging_message, fingerprints);
        ap.mPositiveButtonText = getString(android.R.string.ok);
        ap.mNegativeButtonText = getString(android.R.string.cancel);
        ap.mPositiveButtonListener = this;
        ap.mNegativeButtonListener = this;

        // add "always allow" checkbox
        LayoutInflater inflater = LayoutInflater.from(ap.mContext);
        View checkbox = inflater.inflate(com.android.internal.R.layout.always_use_checkbox, null);
        mAlwaysAllow = (CheckBox)checkbox.findViewById(com.android.internal.R.id.alwaysUse);
        mAlwaysAllow.setText(getString(R.string.usb_debugging_always));
        ap.mView = checkbox;

        setupAlert();
    }

    private class UsbDisconnectedReceiver extends BroadcastReceiver {
        private final Activity mActivity;
        public UsbDisconnectedReceiver(Activity activity) {
            mActivity = activity;
        }

        @Override
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (!UsbManager.ACTION_USB_STATE.equals(action)) {
                return;
            }
            boolean connected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
            if (!connected) {
                mActivity.finish();
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_STATE);
        registerReceiver(mDisconnectedReceiver, filter);
    }

    @Override
    protected void onStop() {
        if (mDisconnectedReceiver != null) {
            unregisterReceiver(mDisconnectedReceiver);
        }
        super.onStop();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        boolean allow = (which == AlertDialog.BUTTON_POSITIVE);
        boolean alwaysAllow = allow && mAlwaysAllow.isChecked();
        try {
            IBinder b = ServiceManager.getService(USB_SERVICE);
            IUsbManager service = IUsbManager.Stub.asInterface(b);
            if (allow) {
                service.allowUsbDebugging(alwaysAllow, mKey);
            } else {
                service.denyUsbDebugging();
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to notify Usb service", e);
        }
        finish();
    }
}
