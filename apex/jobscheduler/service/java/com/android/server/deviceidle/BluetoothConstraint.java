/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.deviceidle;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.DeviceIdleInternal;

// TODO: Should we part of the apex, or the platform??

/**
 * Track whether there are any active Bluetooth devices connected.
 */
public class BluetoothConstraint implements IDeviceIdleConstraint {
    private static final String TAG = BluetoothConstraint.class.getSimpleName();
    private static final long INACTIVITY_TIMEOUT_MS = 20 * 60 * 1000L;

    private final Context mContext;
    private final Handler mHandler;
    private final DeviceIdleInternal mLocalService;
    private final BluetoothManager mBluetoothManager;

    private volatile boolean mConnected = true;
    private volatile boolean mMonitoring = false;

    public BluetoothConstraint(
            Context context, Handler handler, DeviceIdleInternal localService) {
        mContext = context;
        mHandler = handler;
        mLocalService = localService;
        mBluetoothManager = mContext.getSystemService(BluetoothManager.class);
    }

    @Override
    public synchronized void startMonitoring() {
        // Start by assuming we have a connected bluetooth device.
        mConnected = true;
        mMonitoring = true;

        // Register a receiver to get updates on bluetooth devices disconnecting or the
        // adapter state changing.
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);

        // Some devices will try to stay connected indefinitely. Set a timeout to ignore them.
        mHandler.sendMessageDelayed(
                Message.obtain(mHandler, mTimeoutCallback), INACTIVITY_TIMEOUT_MS);

        // Now we have the receiver registered, make a direct check for connected devices.
        updateAndReportActiveLocked();
    }

    @Override
    public synchronized void stopMonitoring() {
        mContext.unregisterReceiver(mReceiver);
        mHandler.removeCallbacks(mTimeoutCallback);
        mMonitoring = false;
    }

    private synchronized void cancelMonitoringDueToTimeout() {
        if (mMonitoring) {
            mMonitoring = false;
            mLocalService.onConstraintStateChanged(this, /* active= */ false);
        }
    }

    /**
     * Check the latest data from BluetoothManager and let DeviceIdleController know whether we
     * have connected devices (for example TV remotes / gamepads) and thus want to stay awake.
     */
    @GuardedBy("this")
    private void updateAndReportActiveLocked() {
        final boolean connected = isBluetoothConnected(mBluetoothManager);
        if (connected != mConnected) {
            mConnected = connected;
            // If we lost all of our connections, we are on track to going into idle state.
            mLocalService.onConstraintStateChanged(this, /* active= */ mConnected);
        }
    }

    /**
     * True if the bluetooth adapter exists, is enabled, and has at least one GATT device connected.
     */
    @VisibleForTesting
    static boolean isBluetoothConnected(BluetoothManager bluetoothManager) {
        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter != null && adapter.isEnabled()) {
            return bluetoothManager.getConnectedDevices(BluetoothProfile.GATT).size() > 0;
        }
        return false;
    }

    /**
     * Registered in {@link #startMonitoring()}, unregistered in {@link #stopMonitoring()}.
     */
    @VisibleForTesting
    final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
                mLocalService.exitIdle("bluetooth");
            } else {
                updateAndReportActiveLocked();
            }
        }
    };

    private final Runnable mTimeoutCallback = () -> cancelMonitoringDueToTimeout();
}
