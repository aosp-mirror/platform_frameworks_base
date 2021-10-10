/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server;

import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.Context;
import android.content.res.Resources;
import android.provider.Settings;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Helper class that handles callout and callback methods without
 * complex logic.
 */
public class BluetoothModeChangeHelper {
    private volatile BluetoothA2dp mA2dp;
    private volatile BluetoothHearingAid mHearingAid;
    private final BluetoothAdapter mAdapter;
    private final Context mContext;

    BluetoothModeChangeHelper(Context context) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mContext = context;

        mAdapter.getProfileProxy(mContext, mProfileServiceListener, BluetoothProfile.A2DP);
        mAdapter.getProfileProxy(mContext, mProfileServiceListener,
                BluetoothProfile.HEARING_AID);
    }

    private final ServiceListener mProfileServiceListener = new ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            // Setup Bluetooth profile proxies
            switch (profile) {
                case BluetoothProfile.A2DP:
                    mA2dp = (BluetoothA2dp) proxy;
                    break;
                case BluetoothProfile.HEARING_AID:
                    mHearingAid = (BluetoothHearingAid) proxy;
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            // Clear Bluetooth profile proxies
            switch (profile) {
                case BluetoothProfile.A2DP:
                    mA2dp = null;
                    break;
                case BluetoothProfile.HEARING_AID:
                    mHearingAid = null;
                    break;
                default:
                    break;
            }
        }
    };

    @VisibleForTesting
    public boolean isA2dpOrHearingAidConnected() {
        return isA2dpConnected() || isHearingAidConnected();
    }

    @VisibleForTesting
    public boolean isBluetoothOn() {
        final BluetoothAdapter adapter = mAdapter;
        if (adapter == null) {
            return false;
        }
        return adapter.getLeState() == BluetoothAdapter.STATE_ON;
    }

    @VisibleForTesting
    public boolean isAirplaneModeOn() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    @VisibleForTesting
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    public void onAirplaneModeChanged(BluetoothManagerService managerService) {
        managerService.onAirplaneModeChanged();
    }

    @VisibleForTesting
    public int getSettingsInt(String name) {
        return Settings.Global.getInt(mContext.getContentResolver(),
                name, 0);
    }

    @VisibleForTesting
    public void setSettingsInt(String name, int value) {
        Settings.Global.putInt(mContext.getContentResolver(),
                name, value);
    }

    @VisibleForTesting
    public void showToastMessage() {
        Resources r = mContext.getResources();
        final CharSequence text = r.getString(
                R.string.bluetooth_airplane_mode_toast, 0);
        Toast.makeText(mContext, text, Toast.LENGTH_LONG).show();
    }

    private boolean isA2dpConnected() {
        final BluetoothA2dp a2dp = mA2dp;
        if (a2dp == null) {
            return false;
        }
        return a2dp.getConnectedDevices().size() > 0;
    }

    private boolean isHearingAidConnected() {
        final BluetoothHearingAid hearingAid = mHearingAid;
        if (hearingAid == null) {
            return false;
        }
        return hearingAid.getConnectedDevices().size() > 0;
    }
}
