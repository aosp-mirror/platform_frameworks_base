/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.systemui.R;

public class UsbDebuggingSecondaryUserActivity extends AlertActivity
        implements DialogInterface.OnClickListener {
    private UsbDisconnectedReceiver mDisconnectedReceiver;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        if (SystemProperties.getInt("service.adb.tcp.port", 0) == 0) {
            mDisconnectedReceiver = new UsbDisconnectedReceiver(this);
        }

        final AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = getString(R.string.usb_debugging_secondary_user_title);
        UserInfo user = UserManager.get(this).getUserInfo(UserHandle.USER_OWNER);
        ap.mMessage = getString(R.string.usb_debugging_secondary_user_message, user.name);
        ap.mPositiveButtonText = getString(android.R.string.ok);
        ap.mPositiveButtonListener = this;

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
            if (UsbManager.ACTION_USB_STATE.equals(action)) {
                boolean connected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
                if (!connected) {
                    mActivity.finish();
                }
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
        finish();
    }
}
