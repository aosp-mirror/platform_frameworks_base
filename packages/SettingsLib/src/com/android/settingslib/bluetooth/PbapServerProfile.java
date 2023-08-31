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

package com.android.settingslib.bluetooth;

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothPbap;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.R;

/**
 * PBAPServer Profile
 */
public class PbapServerProfile implements LocalBluetoothProfile {
    private static final String TAG = "PbapServerProfile";

    private BluetoothPbap mService;
    private boolean mIsProfileReady;

    @VisibleForTesting
    public static final String NAME = "PBAP Server";

    // Order of this profile in device profiles list
    private static final int ORDINAL = 6;

    // The UUIDs indicate that remote device might access pbap server
    static final ParcelUuid[] PBAB_CLIENT_UUIDS = {
        BluetoothUuid.HSP,
        BluetoothUuid.HFP,
        BluetoothUuid.PBAP_PCE
    };

    // These callbacks run on the main thread.
    private final class PbapServiceListener
            implements BluetoothProfile.ServiceListener {

        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mService = (BluetoothPbap) proxy;
            mIsProfileReady=true;
        }

        @Override
        public void onServiceDisconnected(int profile) {
            mIsProfileReady=false;
        }
    }

    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.PBAP;
    }

    PbapServerProfile(Context context) {
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(context, new PbapServiceListener(),
                BluetoothProfile.PBAP);
    }

    public boolean accessProfileEnabled() {
        return true;
    }

    public boolean isAutoConnectable() {
        return false;
    }

    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) return BluetoothProfile.STATE_DISCONNECTED;
        return mService.getConnectionState(device);
    }

    @Override
    public boolean isEnabled(BluetoothDevice device) {
        return false;
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device) {
        return -1;
    }

    @Override
    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        boolean isSuccessful = false;
        if (mService == null) {
            return false;
        }

        if (!enabled) {
            isSuccessful = mService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN);
        }

        return isSuccessful;
    }

    public String toString() {
        return NAME;
    }

    public int getOrdinal() {
        return ORDINAL;
    }

    public int getNameResource(BluetoothDevice device) {
        return R.string.bluetooth_profile_pbap;
    }

    public int getSummaryResourceForDevice(BluetoothDevice device) {
        return R.string.bluetooth_profile_pbap_summary;
    }

    public int getDrawableResource(BluetoothClass btClass) {
        return com.android.internal.R.drawable.ic_phone;
    }

    protected void finalize() {
        Log.d(TAG, "finalize()");
        if (mService != null) {
            try {
                BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.PBAP,
                        mService);
                mService = null;
            }catch (Throwable t) {
                Log.w(TAG, "Error cleaning up PBAP proxy", t);
            }
        }
    }
}
