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
import android.debug.IAdbManager;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.systemui.res.R;
import com.android.systemui.broadcast.BroadcastDispatcher;

import javax.inject.Inject;

public class UsbDebuggingActivity extends AlertActivity
                                  implements DialogInterface.OnClickListener {
    private static final String TAG = "UsbDebuggingActivity";

    private CheckBox mAlwaysAllow;
    private UsbDisconnectedReceiver mDisconnectedReceiver;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private String mKey;
    private boolean mServiceNotified;

    @Inject
    public UsbDebuggingActivity(BroadcastDispatcher broadcastDispatcher) {
        super();
        mBroadcastDispatcher = broadcastDispatcher;
    }

    @Override
    public void onCreate(Bundle icicle) {
        Window window = getWindow();
        window.addSystemFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);

        super.onCreate(icicle);

        // Emulator does not support reseating the usb cable to reshow the dialog.
        if (SystemProperties.getInt("service.adb.tcp.port", 0) == 0 && !Build.IS_EMULATOR) {
            mDisconnectedReceiver = new UsbDisconnectedReceiver(this);
            IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_STATE);
            mBroadcastDispatcher.registerReceiver(mDisconnectedReceiver, filter);
        }

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

    private class UsbDisconnectedReceiver extends BroadcastReceiver {
        private final Activity mActivity;
        UsbDisconnectedReceiver(Activity activity) {
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
                Log.d(TAG, "USB disconnected, notifying service");
                notifyService(false);
                mActivity.finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (mDisconnectedReceiver != null) {
            mBroadcastDispatcher.unregisterReceiver(mDisconnectedReceiver);
        }
        // Only notify the service if the activity is finishing; if onDestroy has been called due to
        // a configuration change then allow the user to still authorize the connection the next
        // time the activity is in the foreground.
        if (isFinishing()) {
            // If the ADB service has not yet been notified due to this dialog being closed in some
            // other way then notify the service to deny the connection to ensure system_server
            // sends a response to adbd.
            if (!mServiceNotified) {
                notifyService(false);
            }
        }
        super.onDestroy();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        boolean allow = (which == AlertDialog.BUTTON_POSITIVE);
        boolean alwaysAllow = allow && mAlwaysAllow.isChecked();
        notifyService(allow, alwaysAllow);
        finish();
    }

    /**
     * Notifies the ADB service as to whether the current ADB request should be allowed; if the
     * request is allowed it is only allowed for this session, and the user should be prompted again
     * on subsequent requests from this key.
     *
     * @param allow whether the connection should be allowed for this session
     */
    private void notifyService(boolean allow) {
        notifyService(allow, false);
    }

    /**
     * Notifies the ADB service as to whether the current ADB request should be allowed, and if
     * subsequent requests from this key should be allowed without user consent.
     *
     * @param allow whether the connection should be allowed
     * @param alwaysAllow whether subsequent requests from this key should be allowed without user
     *                    consent
     */
    private void notifyService(boolean allow, boolean alwaysAllow) {
        try {
            IBinder b = ServiceManager.getService(ADB_SERVICE);
            IAdbManager service = IAdbManager.Stub.asInterface(b);
            if (allow) {
                service.allowDebugging(alwaysAllow, mKey);
            } else {
                service.denyDebugging();
            }
            mServiceNotified = true;
        } catch (Exception e) {
            Log.e(TAG, "Unable to notify Usb service", e);
        }
    }
}
