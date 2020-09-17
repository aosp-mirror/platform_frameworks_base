/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadsetClient;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothProfile.ServiceListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

import com.android.systemui.statusbar.policy.BatteryController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * A {@link BatteryController} that is specific to the Auto use-case. For Auto, the battery icon
 * displays the battery status of a device that is connected via bluetooth and not the system's
 * battery.
 */
public class CarBatteryController extends BroadcastReceiver implements BatteryController {
    private static final String TAG = "CarBatteryController";

    // According to the Bluetooth HFP 1.5 specification, battery levels are indicated by a
    // value from 1-5, where these values represent the following:
    // 0%% - 0, 1-25%% - 1, 26-50%% - 2, 51-75%% - 3, 76-99%% - 4, 100%% - 5
    // As a result, set the level as the average within that range.
    private static final int BATTERY_LEVEL_EMPTY = 0;
    private static final int BATTERY_LEVEL_1 = 12;
    private static final int BATTERY_LEVEL_2 = 28;
    private static final int BATTERY_LEVEL_3 = 63;
    private static final int BATTERY_LEVEL_4 = 87;
    private static final int BATTERY_LEVEL_FULL = 100;

    private static final int INVALID_BATTERY_LEVEL = -1;

    private final Context mContext;

    private final BluetoothAdapter mAdapter = BluetoothAdapter.getDefaultAdapter();
    private final ArrayList<BatteryStateChangeCallback> mChangeCallbacks = new ArrayList<>();
    private BluetoothHeadsetClient mBluetoothHeadsetClient;
    private final ServiceListener mHfpServiceListener = new ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HEADSET_CLIENT) {
                mBluetoothHeadsetClient = (BluetoothHeadsetClient) proxy;
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HEADSET_CLIENT) {
                mBluetoothHeadsetClient = null;
            }
        }
    };
    private int mLevel;
    private BatteryViewHandler mBatteryViewHandler;

    public CarBatteryController(Context context) {
        mContext = context;

        if (mAdapter == null) {
            return;
        }

        mAdapter.getProfileProxy(context.getApplicationContext(), mHfpServiceListener,
                BluetoothProfile.HEADSET_CLIENT);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("CarBatteryController state:");
        pw.print("    mLevel=");
        pw.println(mLevel);
    }

    @Override
    public void setPowerSaveMode(boolean powerSave) {
        // No-op. No power save mode for the car.
    }

    @Override
    public void addCallback(BatteryController.BatteryStateChangeCallback cb) {
        mChangeCallbacks.add(cb);

        // There is no way to know if the phone is plugged in or charging via bluetooth, so pass
        // false for these values.
        cb.onBatteryLevelChanged(mLevel, false /* pluggedIn */, false /* charging */);
        cb.onPowerSaveChanged(false /* isPowerSave */);
    }

    @Override
    public void removeCallback(BatteryController.BatteryStateChangeCallback cb) {
        mChangeCallbacks.remove(cb);
    }

    /** Sets {@link BatteryViewHandler}. */
    public void addBatteryViewHandler(BatteryViewHandler batteryViewHandler) {
        mBatteryViewHandler = batteryViewHandler;
    }

    /** Starts listening for bluetooth broadcast messages. */
    public void startListening() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadsetClient.ACTION_AG_EVENT);
        mContext.registerReceiver(this, filter);
    }

    /** Stops listening for bluetooth broadcast messages. */
    public void stopListening() {
        mContext.unregisterReceiver(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onReceive(). action: " + action);
        }

        if (BluetoothHeadsetClient.ACTION_AG_EVENT.equals(action)) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Received ACTION_AG_EVENT");
            }

            int batteryLevel = intent.getIntExtra(BluetoothHeadsetClient.EXTRA_BATTERY_LEVEL,
                    INVALID_BATTERY_LEVEL);

            updateBatteryLevel(batteryLevel);

            if (batteryLevel != INVALID_BATTERY_LEVEL && mBatteryViewHandler != null) {
                mBatteryViewHandler.showBatteryView();
            }
        } else if (BluetoothHeadsetClient.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            int newState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                int oldState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
                Log.d(TAG, "ACTION_CONNECTION_STATE_CHANGED event: "
                        + oldState + " -> " + newState);

            }
            BluetoothDevice device =
                    (BluetoothDevice) intent.getExtra(BluetoothDevice.EXTRA_DEVICE);
            updateBatteryIcon(device, newState);
        }
    }

    /**
     * Converts the battery level to a percentage that can be displayed on-screen and notifies
     * any {@link BatteryStateChangeCallback}s of this.
     */
    private void updateBatteryLevel(int batteryLevel) {
        if (batteryLevel == INVALID_BATTERY_LEVEL) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Battery level invalid. Ignoring.");
            }
            return;
        }

        // The battery level is a value between 0-5. Let the default battery level be 0.
        switch (batteryLevel) {
            case 5:
                mLevel = BATTERY_LEVEL_FULL;
                break;
            case 4:
                mLevel = BATTERY_LEVEL_4;
                break;
            case 3:
                mLevel = BATTERY_LEVEL_3;
                break;
            case 2:
                mLevel = BATTERY_LEVEL_2;
                break;
            case 1:
                mLevel = BATTERY_LEVEL_1;
                break;
            case 0:
            default:
                mLevel = BATTERY_LEVEL_EMPTY;
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Battery level: " + batteryLevel + "; setting mLevel as: " + mLevel);
        }

        notifyBatteryLevelChanged();
    }

    /**
     * Updates the display of the battery icon depending on the given connection state from the
     * given {@link BluetoothDevice}.
     */
    private void updateBatteryIcon(BluetoothDevice device, int newState) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Device connected");
            }

            if (mBatteryViewHandler != null) {
                mBatteryViewHandler.showBatteryView();
            }

            if (mBluetoothHeadsetClient == null || device == null) {
                return;
            }

            // Check if battery information is available and immediately update.
            Bundle featuresBundle = mBluetoothHeadsetClient.getCurrentAgEvents(device);
            if (featuresBundle == null) {
                return;
            }

            int batteryLevel = featuresBundle.getInt(BluetoothHeadsetClient.EXTRA_BATTERY_LEVEL,
                    INVALID_BATTERY_LEVEL);
            updateBatteryLevel(batteryLevel);
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Device disconnected");
            }

            if (mBatteryViewHandler != null) {
                mBatteryViewHandler.hideBatteryView();
            }
        }
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        // TODO: Car demo mode.
    }

    @Override
    public boolean isPluggedIn() {
        return true;
    }

    @Override
    public boolean isPowerSave() {
        // Power save is not valid for the car, so always return false.
        return false;
    }

    @Override
    public boolean isAodPowerSave() {
        return false;
    }

    private void notifyBatteryLevelChanged() {
        for (int i = 0, size = mChangeCallbacks.size(); i < size; i++) {
            mChangeCallbacks.get(i)
                    .onBatteryLevelChanged(mLevel, false /* pluggedIn */, false /* charging */);
        }
    }

    /**
     * An interface indicating the container of a View that will display what the information
     * in the {@link CarBatteryController}.
     */
    public interface BatteryViewHandler {
        /** Hides the battery view. */
        void hideBatteryView();

        /** Shows the battery view. */
        void showBatteryView();
    }

}
