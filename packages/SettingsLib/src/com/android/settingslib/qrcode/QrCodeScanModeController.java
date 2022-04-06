/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.qrcode;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.content.Context;
import android.util.Log;

import com.android.settingslib.bluetooth.BluetoothUtils;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcastAssistant;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcastMetadata;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;

public class QrCodeScanModeController {

    private static final boolean DEBUG = BluetoothUtils.D;
    private static final String TAG = "QrCodeScanModeController";

    private LocalBluetoothLeBroadcastMetadata mLocalBroadcastMetadata;
    private LocalBluetoothLeBroadcastAssistant mLocalBroadcastAssistant;
    private LocalBluetoothManager mLocalBluetoothManager;
    private LocalBluetoothProfileManager mProfileManager;

    private LocalBluetoothManager.BluetoothManagerCallback
            mOnInitCallback = new LocalBluetoothManager.BluetoothManagerCallback() {
        @Override
        public void onBluetoothManagerInitialized(Context appContext,
                LocalBluetoothManager bluetoothManager) {
            BluetoothUtils.setErrorListener(mErrorListener);
        }
    };

    private BluetoothUtils.ErrorListener
            mErrorListener = new BluetoothUtils.ErrorListener() {
        @Override
        public void onShowError(Context context, String name, int messageResId) {
            if (DEBUG) {
                Log.d(TAG, "Get error when initializing BluetoothManager. ");
            }
        }
    };

    public QrCodeScanModeController(Context context) {
        if (DEBUG) {
            Log.d(TAG, "QrCodeScanModeController constructor.");
        }
        mLocalBluetoothManager = LocalBluetoothManager.getInstance(context, mOnInitCallback);
        mProfileManager = mLocalBluetoothManager.getProfileManager();
        mLocalBroadcastMetadata = new LocalBluetoothLeBroadcastMetadata();
        CachedBluetoothDeviceManager cachedDeviceManager = new CachedBluetoothDeviceManager(context,
                mLocalBluetoothManager);
        mLocalBroadcastAssistant = new LocalBluetoothLeBroadcastAssistant(context,
                cachedDeviceManager, mProfileManager);
    }

    private BluetoothLeBroadcastMetadata convertToBroadcastMetadata(String qrCodeString) {
        return mLocalBroadcastMetadata.convertToBroadcastMetadata(qrCodeString);
    }

    public void addSource(BluetoothDevice sink, String sourceMetadata,
            boolean isGroupOp) {
        mLocalBroadcastAssistant.addSource(sink,
                convertToBroadcastMetadata(sourceMetadata), isGroupOp);
    }
}
