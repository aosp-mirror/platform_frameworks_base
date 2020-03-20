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

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.R;
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
    private AirplaneModeHelper mAirplaneHelper;

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
    void start(AirplaneModeHelper helper) {
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
        mAirplaneHelper.onAirplaneModeChanged(mBluetoothManager);
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

    /**
     * Helper class that handles callout and callback methods without
     * complex logic.
     */
    @VisibleForTesting
    public static class AirplaneModeHelper {
        private volatile BluetoothA2dp mA2dp;
        private volatile BluetoothHearingAid mHearingAid;
        private final BluetoothAdapter mAdapter;
        private final Context mContext;

        AirplaneModeHelper(Context context) {
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
    };
}
