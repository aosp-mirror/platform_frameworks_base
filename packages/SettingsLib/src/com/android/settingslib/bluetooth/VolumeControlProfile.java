/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_ALLOWED;
import static android.bluetooth.BluetoothProfile.CONNECTION_POLICY_FORBIDDEN;

import android.annotation.CallbackExecutor;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothVolumeControl;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** VolumeControlProfile handles Bluetooth Volume Control Controller role */
public class VolumeControlProfile implements LocalBluetoothProfile {
    private static final String TAG = "VolumeControlProfile";
    private static boolean DEBUG = true;
    static final String NAME = "VCP";
    // Order of this profile in device profiles list
    private static final int ORDINAL = 1;

    private Context mContext;
    private final CachedBluetoothDeviceManager mDeviceManager;
    private final LocalBluetoothProfileManager mProfileManager;

    private BluetoothVolumeControl mService;
    private boolean mIsProfileReady;

    // These callbacks run on the main thread.
    private final class VolumeControlProfileServiceListener
            implements BluetoothProfile.ServiceListener {

        @RequiresApi(Build.VERSION_CODES.S)
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (DEBUG) {
                Log.d(TAG, "Bluetooth service connected");
            }
            mService = (BluetoothVolumeControl) proxy;
            // We just bound to the service, so refresh the UI for any connected
            // VolumeControlProfile devices.
            List<BluetoothDevice> deviceList = mService.getConnectedDevices();
            while (!deviceList.isEmpty()) {
                BluetoothDevice nextDevice = deviceList.remove(0);
                CachedBluetoothDevice device = mDeviceManager.findDevice(nextDevice);
                // we may add a new device here, but generally this should not happen
                if (device == null) {
                    if (DEBUG) {
                        Log.d(TAG, "VolumeControlProfile found new device: " + nextDevice);
                    }
                    device = mDeviceManager.addDevice(nextDevice);
                }
                device.onProfileStateChanged(
                        VolumeControlProfile.this, BluetoothProfile.STATE_CONNECTED);
                device.refresh();
            }

            mProfileManager.callServiceConnectedListeners();
            mIsProfileReady = true;
        }

        public void onServiceDisconnected(int profile) {
            if (DEBUG) {
                Log.d(TAG, "Bluetooth service disconnected");
            }
            mProfileManager.callServiceDisconnectedListeners();
            mIsProfileReady = false;
        }
    }

    VolumeControlProfile(
            Context context,
            CachedBluetoothDeviceManager deviceManager,
            LocalBluetoothProfileManager profileManager) {
        mContext = context;
        mDeviceManager = deviceManager;
        mProfileManager = profileManager;

        BluetoothAdapter.getDefaultAdapter()
                .getProfileProxy(
                        context,
                        new VolumeControlProfile.VolumeControlProfileServiceListener(),
                        BluetoothProfile.VOLUME_CONTROL);
    }

    /**
     * Registers a {@link BluetoothVolumeControl.Callback} that will be invoked during the operation
     * of this profile.
     *
     * <p>Repeated registration of the same <var>callback</var> object will have no effect after the
     * first call to this method, even when the <var>executor</var> is different. API caller would
     * have to call {@link #unregisterCallback(BluetoothVolumeControl.Callback)} with the same
     * callback object before registering it again.
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback user implementation of the {@link BluetoothVolumeControl.Callback}
     * @throws IllegalArgumentException if a null executor or callback is given
     */
    public void registerCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BluetoothVolumeControl.Callback callback) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot register callback.");
            return;
        }
        mService.registerCallback(executor, callback);
    }

    /**
     * Unregisters the specified {@link BluetoothVolumeControl.Callback}.
     *
     * <p>The same {@link BluetoothVolumeControl.Callback} object used when calling {@link
     * #registerCallback(Executor, BluetoothVolumeControl.Callback)} must be used.
     *
     * <p>Callbacks are automatically unregistered when application process goes away
     *
     * @param callback user implementation of the {@link BluetoothVolumeControl.Callback}
     * @throws IllegalArgumentException when callback is null or when no callback is registered
     */
    public void unregisterCallback(@NonNull BluetoothVolumeControl.Callback callback) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot unregister callback.");
            return;
        }
        mService.unregisterCallback(callback);
    }

    /**
     * Tells the remote device to set a volume offset to the absolute volume.
     *
     * @param device {@link BluetoothDevice} representing the remote device
     * @param volumeOffset volume offset to be set on the remote device
     */
    public void setVolumeOffset(
            BluetoothDevice device, @IntRange(from = -255, to = 255) int volumeOffset) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot set volume offset.");
            return;
        }
        if (device == null) {
            Log.w(TAG, "Device is null. Cannot set volume offset.");
            return;
        }
        mService.setVolumeOffset(device, volumeOffset);
    }
    /**
     * Provides information about the possibility to set volume offset on the remote device. If the
     * remote device supports Volume Offset Control Service, it is automatically connected.
     *
     * @param device {@link BluetoothDevice} representing the remote device
     * @return {@code true} if volume offset function is supported and available to use on the
     *     remote device. When Bluetooth is off, the return value should always be {@code false}.
     */
    public boolean isVolumeOffsetAvailable(BluetoothDevice device) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot get is volume offset available.");
            return false;
        }
        if (device == null) {
            Log.w(TAG, "Device is null. Cannot get is volume offset available.");
            return false;
        }
        return mService.isVolumeOffsetAvailable(device);
    }

    /**
     * Tells the remote device to set a volume.
     *
     * @param device {@link BluetoothDevice} representing the remote device
     * @param volume volume to be set on the remote device
     * @param isGroupOp whether to set the volume to remote devices within the same CSIP group
     */
    public void setDeviceVolume(
            BluetoothDevice device,
            @IntRange(from = 0, to = 255) int volume,
            boolean isGroupOp) {
        if (mService == null) {
            Log.w(TAG, "Proxy not attached to service. Cannot set volume offset.");
            return;
        }
        if (device == null) {
            Log.w(TAG, "Device is null. Cannot set volume offset.");
            return;
        }
        mService.setDeviceVolume(device, volume, isGroupOp);
    }

    @Override
    public boolean accessProfileEnabled() {
        return false;
    }

    @Override
    public boolean isAutoConnectable() {
        return true;
    }

    /**
     * Gets VolumeControlProfile devices matching connection states{ {@code
     * BluetoothProfile.STATE_CONNECTED}, {@code BluetoothProfile.STATE_CONNECTING}, {@code
     * BluetoothProfile.STATE_DISCONNECTING}}
     *
     * @return Matching device list
     */
    public List<BluetoothDevice> getConnectedDevices() {
        if (mService == null) {
            return new ArrayList<BluetoothDevice>(0);
        }
        return mService.getDevicesMatchingConnectionStates(
                new int[] {
                    BluetoothProfile.STATE_CONNECTED,
                    BluetoothProfile.STATE_CONNECTING,
                    BluetoothProfile.STATE_DISCONNECTING
                });
    }

    @Override
    public int getConnectionStatus(BluetoothDevice device) {
        if (mService == null) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
        return mService.getConnectionState(device);
    }

    @Override
    public boolean isEnabled(BluetoothDevice device) {
        if (mService == null || device == null) {
            return false;
        }
        return mService.getConnectionPolicy(device) > CONNECTION_POLICY_FORBIDDEN;
    }

    @Override
    public int getConnectionPolicy(BluetoothDevice device) {
        if (mService == null || device == null) {
            return CONNECTION_POLICY_FORBIDDEN;
        }
        return mService.getConnectionPolicy(device);
    }

    @Override
    public boolean setEnabled(BluetoothDevice device, boolean enabled) {
        boolean isSuccessful = false;
        if (mService == null || device == null) {
            return false;
        }
        if (DEBUG) {
            Log.d(TAG, device.getAnonymizedAddress() + " setEnabled: " + enabled);
        }
        if (enabled) {
            if (mService.getConnectionPolicy(device) < CONNECTION_POLICY_ALLOWED) {
                isSuccessful = mService.setConnectionPolicy(device, CONNECTION_POLICY_ALLOWED);
            }
        } else {
            isSuccessful = mService.setConnectionPolicy(device, CONNECTION_POLICY_FORBIDDEN);
        }

        return isSuccessful;
    }

    @Override
    public boolean isProfileReady() {
        return mIsProfileReady;
    }

    @Override
    public int getProfileId() {
        return BluetoothProfile.VOLUME_CONTROL;
    }

    public String toString() {
        return NAME;
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    @Override
    public int getNameResource(BluetoothDevice device) {
        return 0; // VCP profile not displayed in UI
    }

    @Override
    public int getSummaryResourceForDevice(BluetoothDevice device) {
        return 0; // VCP profile not displayed in UI
    }

    @Override
    public int getDrawableResource(BluetoothClass btClass) {
        // no icon for VCP
        return 0;
    }
}
