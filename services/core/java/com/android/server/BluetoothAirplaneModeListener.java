/*
 * Copyright 2019 The Android Open Source Project
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
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * The BluetoothAirplaneModeListener handles system airplane mode change callback and checks
 * whether we need to inform BluetoothManagerService on this change.
 *
 * The information of airplane mode turns on would not be passed to the BluetoothManagerService
 * when Bluetooth is on and Bluetooth is in one of the following situations:
 *   1. Bluetooth A2DP is connected.
 *   2. Bluetooth Hearing Aid profile is connected.
 */
class BluetoothAirplaneModeListener {
    private static final String TAG = "BluetoothAirplaneModeListener";
    @VisibleForTesting static final String TOAST_COUNT = "bluetooth_airplane_toast_count";

    private static final int MSG_AIRPLANE_MODE_CHANGED = 0;

    @VisibleForTesting static final int MAX_TOAST_COUNT = 10; // 10 times

    private final BluetoothManagerService mBluetoothManager;
    private final BluetoothAirplaneModeHandler mHandler;
    private BluetoothModeChangeHelper mAirplaneHelper;

    @VisibleForTesting int mToastCount = 0;

    BluetoothAirplaneModeListener(BluetoothManagerService service, Looper looper, Context context) {
        mBluetoothManager = service;

        mHandler = new BluetoothAirplaneModeHandler(looper);
        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), true,
                mAirplaneModeObserver);
    }

    private final ContentObserver mAirplaneModeObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean unused) {
            // Post from system main thread to android_io thread.
            Message msg = mHandler.obtainMessage(MSG_AIRPLANE_MODE_CHANGED);
            mHandler.sendMessage(msg);
        }
    };

    private class BluetoothAirplaneModeHandler extends Handler {
        BluetoothAirplaneModeHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_AIRPLANE_MODE_CHANGED:
                    handleAirplaneModeChange();
                    break;
                default:
                    Log.e(TAG, "Invalid message: " + msg.what);
                    break;
            }
        }
    }

    /**
     * Call after boot complete
     */
    @VisibleForTesting
    void start(BluetoothModeChangeHelper helper) {
        Log.i(TAG, "start");
        mAirplaneHelper = helper;
        mToastCount = mAirplaneHelper.getSettingsInt(TOAST_COUNT);
    }

    @VisibleForTesting
    boolean shouldPopToast() {
        if (mToastCount >= MAX_TOAST_COUNT) {
            return false;
        }
        mToastCount++;
        mAirplaneHelper.setSettingsInt(TOAST_COUNT, mToastCount);
        return true;
    }

    @VisibleForTesting
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_PRIVILEGED)
    void handleAirplaneModeChange() {
        if (shouldSkipAirplaneModeChange()) {
            Log.i(TAG, "Ignore airplane mode change");
            // We have to store Bluetooth state here, so if user turns off Bluetooth
            // after airplane mode is turned on, we don't forget to turn on Bluetooth
            // when airplane mode turns off.
            mAirplaneHelper.setSettingsInt(Settings.Global.BLUETOOTH_ON,
                    BluetoothManagerService.BLUETOOTH_ON_AIRPLANE);
            if (shouldPopToast()) {
                mAirplaneHelper.showToastMessage();
            }
            return;
        }
        if (mAirplaneHelper != null) {
            mAirplaneHelper.onAirplaneModeChanged(mBluetoothManager);
        }
    }

    @VisibleForTesting
    boolean shouldSkipAirplaneModeChange() {
        if (mAirplaneHelper == null) {
            return false;
        }
        if (!mAirplaneHelper.isBluetoothOn() || !mAirplaneHelper.isAirplaneModeOn()
                || !mAirplaneHelper.isA2dpOrHearingAidConnected()) {
            return false;
        }
        return true;
    }
}
