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

package com.android.systemui.wifi;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;
import com.android.systemui.R;

/**
 * Alerts the user that wireless debugging cannot be enabled by a secondary user.
 */
public class WifiDebuggingSecondaryUserActivity extends AlertActivity
        implements DialogInterface.OnClickListener {
    private WifiChangeReceiver mWifiChangeReceiver;
    private WifiManager mWifiManager;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        mWifiChangeReceiver = new WifiChangeReceiver(this);

        final AlertController.AlertParams ap = mAlertParams;
        ap.mTitle = getString(R.string.wifi_debugging_secondary_user_title);
        ap.mMessage = getString(R.string.wifi_debugging_secondary_user_message);
        ap.mPositiveButtonText = getString(android.R.string.ok);
        ap.mPositiveButtonListener = this;

        setupAlert();
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
    public void onClick(DialogInterface dialog, int which) {
        finish();
    }
}
