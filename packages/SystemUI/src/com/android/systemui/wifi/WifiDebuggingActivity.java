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

package com.android.systemui.wifi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.debug.IAdbManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.EventLog;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.Toast;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.systemui.res.R;

/**
 * Alerts the user of an untrusted network when enabling wireless debugging.
 * The user can either deny, allow, or allow with the "always allow on this
 * network" checked.
 */
public class WifiDebuggingActivity extends AlertActivity
                                  implements DialogInterface.OnClickListener {
    private static final String TAG = "WifiDebuggingActivity";

    private CheckBox mAlwaysAllow;
    // Notifies when wifi is disabled, or the network changed
    private WifiChangeReceiver mWifiChangeReceiver;
    private WifiManager mWifiManager;
    private String mBssid;
    private boolean mClicked = false;

    @Override
    public void onCreate(Bundle icicle) {
        Window window = getWindow();
        window.addSystemFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);

        super.onCreate(icicle);


        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        mWifiChangeReceiver = new WifiChangeReceiver(this);

        Intent intent = getIntent();
        String ssid = intent.getStringExtra("ssid");
        mBssid = intent.getStringExtra("bssid");

        if (ssid == null || mBssid == null) {
            finish();
            return;
        }

        final AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = getString(R.string.wifi_debugging_title);
        ap.mMessage = getString(R.string.wifi_debugging_message, ssid, mBssid);
        ap.mPositiveButtonText = getString(R.string.wifi_debugging_allow);
        ap.mNegativeButtonText = getString(android.R.string.cancel);
        ap.mPositiveButtonListener = this;
        ap.mNegativeButtonListener = this;

        // add "always allow" checkbox
        LayoutInflater inflater = LayoutInflater.from(ap.mContext);
        View checkbox = inflater.inflate(com.android.internal.R.layout.always_use_checkbox, null);
        mAlwaysAllow = (CheckBox) checkbox.findViewById(com.android.internal.R.id.alwaysUse);
        mAlwaysAllow.setText(getString(R.string.wifi_debugging_always));
        ap.mView = checkbox;
        window.setCloseOnTouchOutside(false);

        setupAlert();

        // adding touch listener on affirmative button - checks if window is obscured
        // if obscured, do not let user give permissions (could be tapjacking involved)
        final View.OnTouchListener filterTouchListener = (View v, MotionEvent event) -> {
            // Filter obscured touches by consuming them.
            if (((event.getFlags() & MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0)
                    || ((event.getFlags() & MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0)) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    // TODO: need a different value for safety net?
                    EventLog.writeEvent(0x534e4554, "62187985"); // safety net logging
                    Toast.makeText(v.getContext(),
                            R.string.touch_filtered_warning,
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        };
        mAlert.getButton(BUTTON_POSITIVE).setOnTouchListener(filterTouchListener);

    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
        super.onWindowAttributesChanged(params);
    }

    private class WifiChangeReceiver extends BroadcastReceiver {
        private final Activity mActivity;
        WifiChangeReceiver(Activity activity) {
            mActivity = activity;
        }

        @Override
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED);
                if (state == WifiManager.WIFI_STATE_DISABLED) {
                    mActivity.finish();
                }
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(
                        WifiManager.EXTRA_NETWORK_INFO);
                if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
                    if (!networkInfo.isConnected()) {
                        mActivity.finish();
                        return;
                    }
                    WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
                    if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
                        mActivity.finish();
                        return;
                    }
                    String bssid = wifiInfo.getBSSID();
                    if (bssid == null || bssid.isEmpty()) {
                        mActivity.finish();
                        return;
                    }
                    if (!bssid.equals(mBssid)) {
                        mActivity.finish();
                        return;
                    }
                }
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(mWifiChangeReceiver, filter);
        // Close quick shade
        sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));

    }

    @Override
    protected void onStop() {
        if (mWifiChangeReceiver != null) {
            unregisterReceiver(mWifiChangeReceiver);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // In the case where user dismissed the dialog, we don't get an onClick event.
        // In that case, tell adb to deny the network connection.
        if (!mClicked) {
            try {
                IBinder b = ServiceManager.getService(ADB_SERVICE);
                IAdbManager service = IAdbManager.Stub.asInterface(b);
                service.denyWirelessDebugging();
            } catch (Exception e) {
                Log.e(TAG, "Unable to notify Adb service", e);
            }
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        mClicked = true;
        boolean allow = (which == AlertDialog.BUTTON_POSITIVE);
        boolean alwaysAllow = allow && mAlwaysAllow.isChecked();
        try {
            IBinder b = ServiceManager.getService(ADB_SERVICE);
            IAdbManager service = IAdbManager.Stub.asInterface(b);
            if (allow) {
                service.allowWirelessDebugging(alwaysAllow, mBssid);
            } else {
                service.denyWirelessDebugging();
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to notify Adb service", e);
        }
        finish();
    }
}
