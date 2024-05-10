/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.deviceinfo;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.R;
import com.android.settingslib.core.lifecycle.Lifecycle;
import com.android.settingslib.utils.ThreadUtils;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Preference controller for bluetooth address
 */
public abstract class AbstractBluetoothAddressPreferenceController
        extends AbstractConnectivityPreferenceController {

    @VisibleForTesting
    static final String KEY_BT_ADDRESS = "bt_address";

    private static final String[] CONNECTIVITY_INTENTS = {
            BluetoothAdapter.ACTION_STATE_CHANGED
    };

    private Preference mBtAddress;

    public AbstractBluetoothAddressPreferenceController(Context context, Lifecycle lifecycle) {
        super(context, lifecycle);
    }

    @Override
    public boolean isAvailable() {
        return BluetoothAdapter.getDefaultAdapter() != null;
    }

    @Override
    public String getPreferenceKey() {
        return KEY_BT_ADDRESS;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mBtAddress = screen.findPreference(KEY_BT_ADDRESS);
        updateConnectivity();
    }

    @Override
    protected String[] getConnectivityIntents() {
        return CONNECTIVITY_INTENTS;
    }

    @SuppressLint("HardwareIds")
    @Override
    protected void updateConnectivity() {
        BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
        if (bluetooth != null && mBtAddress != null) {
            ListenableFuture<String> future = ThreadUtils.getBackgroundExecutor()
                    .submit(() -> bluetooth.isEnabled() ? bluetooth.getAddress() : null);
            Futures.addCallback(future, new FutureCallback<>() {
                @Override
                public void onSuccess(@Nullable String address) {
                    if (!TextUtils.isEmpty(address)) {
                        // Convert the address to lowercase for consistency with the wifi MAC
                        // address.
                        mBtAddress.setSummary(address.toLowerCase());
                    } else {
                        mBtAddress.setSummary(R.string.status_unavailable);
                    }
                }

                @Override
                public void onFailure(Throwable t) {}
            }, mContext.getMainExecutor());
        }
    }
}
